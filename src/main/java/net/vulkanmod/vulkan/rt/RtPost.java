package net.vulkanmod.vulkan.rt;

// === RT PATCH (M8.27): ПОСТ-ПРОХОД (тонмаппинг + экранные эффекты) ===
// Раньше RT-шейдер сам делал ВСЁ: трассировку, экранные эффекты (огонь/яд/урон), AgX-тонмаппинг,
// гамму — и клал в SSBO готовый 8-битный пиксель. Для DLSS так нельзя: нейросеть ждёт на входе
// ШУМНЫЙ ЛИНЕЙНЫЙ HDR-цвет ДО тонмаппинга (тонмаппер ломает ей яркостную статистику), а экранные
// эффекты она бы приняла за часть сцены и размазала.
//
// Теперь конвейер такой:
//   RtSnapshot (трассировка) -> HDR-образ (RGBA16F, линейный)
//   [ здесь встанет DLSS Ray Reconstruction ]
//   RtPost (этот класс) -> эффекты + AgX + гамма -> LDR-образ -> блит на экран (RtScreen)
//
// Push-константы — ТОТ ЖЕ 256-байтный блок, что у RtSnapshot (общий writePush), чтобы эффекты
// читали ровно те же поля и раскладка не разъезжалась между двумя шейдерами.

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class RtPost {

    private static final String SRC = """
            #version 460
            layout(local_size_x = 8, local_size_y = 8) in;

            layout(binding = 0, rgba16f) uniform readonly  image2D hdrIn;    // линейный HDR из трассировки
            layout(binding = 1, rgba8)   uniform writeonly image2D ldrOut;   // то, что уйдёт на экран
            // guide-буферы — только для ОТЛАДОЧНОГО просмотра (F6): убедиться глазами, что они верные
            layout(binding = 2, r32f)    uniform readonly image2D gDepth;
            layout(binding = 3, rg16f)   uniform readonly image2D gMotion;
            layout(binding = 4, rgba16f) uniform readonly image2D gNormal;
            layout(binding = 5, rgba16f) uniform readonly image2D gDiff;
            layout(binding = 6, rgba16f) uniform readonly image2D gSpec;

            // === АВТОЭКСПОЗИЦИЯ (M8.32) ===
            // Так делает и Eclipse: тонмап у него один на все случаи жизни, а за день/ночь отвечает
            // экспозиция. Считаем СРЕДНЮЮ ЛОГАРИФМИЧЕСКУЮ яркость кадра — арифметическая не годится:
            // одно солнце в кадре перебило бы всё остальное, и сцена ушла бы в темноту.
            layout(std430, binding = 7) buffer Exposure {
                uint  accum;      // сумма log2(яркость), в целых (fixed point)
                uint  count;      // сколько пикселей замерили
                float exposure;   // текущая экспозиция (её и применяем)
                float pad;
                // === M8.86 НАСТРОЙКИ ИЗОБРАЖЕНИЯ (порт ручек Eclipse: Post_Processing/Color) ===
                // Java пишет их каждый кадр, шейдер только читает. Push-константы забиты (256 Б),
                // а этот буфер уже примотан к посту и лежит в host-visible памяти — везём тут.
                float mblur;      // размытие в движении: доля вектора движения (0 = выкл)
                float vign;       // виньетка (0..1)
                float grain;      // зерно плёнки (0..1)
                float chroma;     // хроматическая аберрация (0..1)
                float expBias;    // сдвиг экспозиции, СТОПЫ (+1 = вдвое светлее)
                float sat;        // насыщенность (1 = как снято)
                float flare;      // блики линзы от солнца/луны (0 = выкл)
                float rasterMode; // M8.122: вход — растровый LDR-кадр (фолбэк «Шейдеры» без RT)
            } ex;

            layout(push_constant) uniform Push {
                vec4 origin;      // xyz камера,  w = ШИРИНА кадра
                vec4 forward;     // xyz вперёд,  w = ВЫСОТА кадра
                vec4 up;
                vec4 left;
                vec4 params;      // y = время (тики)
                vec4 wparams;
                vec4 handCol;
                vec4 outlineInfo; // y = ГОРИМ (0..1)
                vec4 obox[3];     // ⚠️ obox[0].x переиспользован постом под РЕЖИМ ОТЛАДКИ (см. record)
                vec4 skyP;
                vec4 fx1;         // z = яд, w = иссушение
                vec4 fx2;         // z = одно сердце, w = три сердца
                vec4 fx3;         // x = лёгкий урон, y = критический урон
                vec4 skyP2;
            } pc;

            // === M8.123 РАСТРОВЫЙ ФОЛБЭК: глубина растра — единственный g-буфер ванили ===
            // Из неё восстанавливаем позицию в ОСЯХ КАМЕРЫ (право/верх/вперёд): NDC-глубина
            // раскручивается в метры (как в отладке F6), а (u,v) пикселя с тангенсами половин
            // FOV дают направление. В RT-кадрах тут заглушка 1x1 (ветка не исполняется).
            layout(binding = 8) uniform sampler2D rasterDepth;
            // M8.124: глубина ТВЕРДИ — снимок ДО прозрачного слоя. Где финальная глубина ближе —
            // пиксель закрыт водой/стеклом, а разница глубин даёт ТОЛЩИНУ воды (поглощение).
            layout(binding = 9) uniform sampler2D solidDepth;
            vec3 rvPos(ivec2 p, vec2 res, float nr, float fr, vec2 tanXY) {
                float d = texelFetch(rasterDepth, p, 0).r;
                float vz = (nr * fr) / max(fr - d * (fr - nr), 1e-6);
                float uu = ((float(p.x) + 0.5) / res.x) * 2.0 - 1.0;
                float vv = 1.0 - ((float(p.y) + 0.5) / res.y) * 2.0;
                return vec3(uu * tanXY.x, vv * tanXY.y, 1.0) * vz;
            }

            // --- симплекс-шум (языки пламени) ---
            vec3 permute3(vec3 x){ return mod(((x*34.0)+1.0)*x, 289.0); }
            float snoise(vec2 v){
                const vec4 C = vec4(0.211324865405187, 0.366025403784439,
                                   -0.577350269189626, 0.024390243902439);
                vec2 i  = floor(v + dot(v, C.yy));
                vec2 x0 = v -   i + dot(i, C.xx);
                vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
                vec4 x12 = x0.xyxy + C.xxzz;
                x12.xy -= i1;
                i = mod(i, 289.0);
                vec3 p = permute3( permute3( i.y + vec3(0.0, i1.y, 1.0)) + i.x + vec3(0.0, i1.x, 1.0));
                vec3 m = max(0.5 - vec3(dot(x0,x0), dot(x12.xy,x12.xy), dot(x12.zw,x12.zw)), 0.0);
                m = m*m; m = m*m;
                vec3 x = 2.0 * fract(p * C.www) - 1.0;
                vec3 h = abs(x) - 0.5;
                vec3 ox = floor(x + 0.5);
                vec3 a0 = x - ox;
                m *= 1.79284291400159 - 0.85373472095314 * (a0*a0 + h*h);
                vec3 g;
                g.x  = a0.x  * x0.x  + h.x  * x0.y;
                g.yz = a0.yz * x12.xz + h.yz * x12.yw;
                return 130.0 * dot(m, g);
            }

            // --- AgX (MIT: Benjamin Wrensch — стандартные матрицы), КАЛИБРОВКА НАША ---
            // Окно экспозиции (minEv/maxEv) и look (контраст/насыщенность) — собственные
            // значения, НЕ совпадают с пресетами Eclipse (сознательное отличие грейда).
            vec3 agxContrast(vec3 x) {
                vec3 x2 = x * x, x4 = x2 * x2;
                return 15.5 * x4 * x2 - 40.14 * x4 * x + 31.96 * x4
                     - 6.868 * x2 * x + 0.4298 * x2 + 0.1191 * x - 0.00232;
            }
            vec3 agxLook(vec3 v) {
                v = pow(v, vec3(1.32));
                float l = dot(v, vec3(0.2126, 0.7152, 0.0722));
                return l + 1.42 * (v - l);
            }
            vec3 ToneMap_AgX(vec3 color) {
                const mat3 inMat = mat3(
                    0.842479062253094, 0.0423282422610123, 0.0423756549057051,
                    0.0784335999999992, 0.878468636469772, 0.0784336,
                    0.0792237451477643, 0.0791661274605434, 0.879142973793104);
                const mat3 outMat = mat3(
                    1.19687900512017, -0.0528968517574562, -0.0529716355144438,
                    -0.0980208811401368, 1.15190312990417, -0.0980434501171241,
                    -0.0990297440797205, -0.0989611768448433, 1.15107367264116);
                const float minEv = -11.9, maxEv = 3.45;   // своё окно (между оригиналом AgX и merlin)
                color = inMat * color;
                color = clamp(log2(max(color, 1e-8)), minEv, maxEv);
                color = (color - minEv) / (maxEv - minEv);
                color = agxContrast(color);
                color = agxLook(color);
                color = outMat * color;
                color = pow(max(vec3(0.0), color), vec3(2.2));
                return clamp(color, 0.0, 1.0);
            }

            void main() {
                ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
                // ⚠️ Пост работает в ПОЛНОМ разрешении (после DLSS), а трассировка и guide-буферы —
                // в РЕНДЕРНОМ (низком). Поэтому у поста два размера, и координаты в исходный кадр
                // надо пересчитывать: без этого при включённом апскейле мы читали бы мусор за краем.
                vec2 srcSize = pc.obox[1].xy;      // разрешение трассировки (guide-буферы)
                int width  = int(pc.obox[1].z);    // разрешение экрана
                int height = int(pc.obox[1].w);
                if (pix.x >= width || pix.y >= height) return;

                // Если DLSS выключен, цвет лежит в рендерном разрешении — масштабируем координату.
                bool dlssOn = int(pc.obox[0].w + 0.5) != 0;
                ivec2 spix = dlssOn ? pix
                                    : ivec2(vec2(pix) * srcSize / vec2(float(width), float(height)));
                ivec2 gpix = ivec2(vec2(pix) * srcSize / vec2(float(width), float(height)));   // guide всегда в рендерном

                vec3 col = imageLoad(hdrIn, spix).rgb;
                // M8.122: РАСТРОВЫЙ ФОЛБЭК — в hdrIn заблитен ванильный растр (sRGB LDR, полное
                // разрешение). Всё, чему нужны guide-буферы или автоэкспозиция, в этом режиме
                // отключено: их данные — мусор прошлого RT-кадра.
                bool rasterIn = ex.rasterMode > 0.5;

                // Разрешение ИСХОДНОГО образа: с DLSS цвет уже растянут до экрана, без него — рендерный.
                vec2 srcRes = dlssOn ? vec2(float(width), float(height)) : srcSize;

                // === M8.86 ХРОМАТИЧЕСКАЯ АБЕРРАЦИЯ (порт CHROMATIC_ABERRATION) ===
                // Линза разводит цвета к краям кадра: красный и синий берём из точек, смещённых
                // ОТ ЦЕНТРА в разные стороны. В середине экрана смещение нулевое — там линза «честна».
                if (ex.chroma > 0.001) {
                    vec2 c = (vec2(spix) / srcRes - 0.5);
                    vec2 off = c * ex.chroma * 6.0;                     // px, растёт к краям
                    vec2 rp = clamp(vec2(spix) + off, vec2(0.0), srcRes - 1.0);
                    vec2 bp = clamp(vec2(spix) - off, vec2(0.0), srcRes - 1.0);
                    col = vec3(imageLoad(hdrIn, ivec2(rp)).r, col.g, imageLoad(hdrIn, ivec2(bp)).b);
                }

                // M8.122: растровый кадр — из sRGB в линейный свет, с запасом яркости ×1.6:
                // плечо AgX прижимает белую точку растра (ровно 1.0) к ~0.77, и без запаса
                // картинка выходила бы блеклой. Дальше конвейер общий: AgX ждёт линейный вход.
                if (rasterIn) col = pow(max(col, vec3(0.0)), vec3(2.2)) * 1.6;

                // === M8.123: SSAO + КОНТАКТНЫЕ ТЕНИ СОЛНЦА (только фолбэк) ===
                // Ваниль не отбрасывает теней вовсе. Глубина даёт геометрию — затеняем сами:
                // прикопчённые углы (SSAO) и короткая тень в сторону солнца (экранный марш).
                // Работаем в ЛИНЕЙНОМ свете, до AgX — тень это свойство света, не плёнки.
                if (rasterIn) {
                    float nr = pc.obox[0].y, fr = pc.obox[0].z;
                    vec2 res2 = vec2(float(width), float(height));
                    vec2 tanXY = pc.params.zw;            // тангенсы половин FOV экрана (см. fillRasterPush)
                    float d0 = texelFetch(rasterDepth, pix, 0).r;
                    if (d0 < 0.999999 && tanXY.x > 1e-4) {   // не небо
                        ivec2 pxc = clamp(pix + ivec2(1, 0), ivec2(0), ivec2(width - 1, height - 1));
                        ivec2 pyc = clamp(pix + ivec2(0, 1), ivec2(0), ivec2(width - 1, height - 1));
                        vec3 P  = rvPos(pix, res2, nr, fr, tanXY);
                        vec3 dx = rvPos(pxc, res2, nr, fr, tanXY) - P;
                        vec3 dy = rvPos(pyc, res2, nr, fr, tanXY) - P;
                        vec3 N = normalize(cross(dy, dx));
                        if (dot(N, P) > 0.0) N = -N;      // нормаль — к камере
                        float h0 = fract(sin(dot(vec2(pix), vec2(12.9898, 78.233))) * 43758.5453);

                        // === M8.124 ВОДА (стиль наших RT-волн, экранными средствами) ===
                        // Маска: финальная глубина ближе снимка тверди -> пиксель под прозрачным
                        // слоем; вода — если поверхность горизонтальна и лежит на «водяной»
                        // высоте блока (~0.80..0.96 от целого Y). Стекло/лёд остаются ванилью.
                        float dS = texelFetch(solidDepth, pix, 0).r;
                        vec3 rightv2 = -pc.left.xyz;
                        vec3 nWorld = rightv2 * N.x + pc.up.xyz * N.y + pc.forward.xyz * N.z;
                        vec3 Wpos = pc.origin.xyz + rightv2 * P.x + pc.up.xyz * P.y + pc.forward.xyz * P.z;
                        float topFr = fract(Wpos.y);
                        bool isWater = (dS > d0 + 1e-7) && nWorld.y > 0.7
                                       && topFr > 0.80 && topFr < 0.96;
                        if (isWater) {
                            // толщина воды вдоль луча: раскручиваем обе глубины в метры
                            float vzS = (nr * fr) / max(fr - dS * (fr - nr), 1e-6);
                            float wd = max(vzS - P.z, 0.0) * length(P) / max(P.z, 1e-4);
                            // поглощение + рассеяние — те же константы, что в RT-воде
                            float day = clamp(pc.skyP.y * 2.5 + 0.15, 0.045, 1.0);
                            vec3 wA = vec3(0.335, 0.106, 0.069);
                            col *= exp(-wA * (0.3 + min(wd, 16.0)));
                            col += vec3(0.030, 0.058, 0.105) * 1.6 * day * (1.0 - exp(-0.30 * wd));
                            // волновая нормаль по мировым XZ (наш стиль, дешёвая сумма синусов)
                            float wt = pc.params.y / 20.0;
                            vec3 nW = normalize(vec3(
                                0.050*sin(Wpos.x*1.7 + wt*2.1) + 0.033*sin(Wpos.z*2.9 - wt*3.0)
                                    + 0.020*sin((Wpos.x + Wpos.z)*4.3 + wt*4.6),
                                1.0,
                                0.050*cos(Wpos.z*1.9 + wt*2.5) + 0.033*cos(Wpos.x*2.5 + wt*2.8)
                                    + 0.020*cos((Wpos.x - Wpos.z)*3.7 - wt*4.0)));
                            // Френель к небу (простое небо: горизонт/зенит по высоте отражённого)
                            vec3 Vw = normalize(Wpos - pc.origin.xyz);
                            vec3 Rw = reflect(Vw, nW);
                            float F = 0.02 + 0.98 * pow(1.0 - max(dot(-Vw, nW), 0.0), 5.0);
                            vec3 skyC = mix(vec3(0.62, 0.72, 0.85), vec3(0.16, 0.34, 0.70),
                                            clamp(Rw.y * 1.4, 0.0, 1.0)) * day;
                            col = mix(col, skyC, clamp(F, 0.0, 1.0) * 0.75);
                            // дорожка солнца по волнам
                            if (pc.skyP.y > 0.0) {
                                float g = pow(max(dot(Rw, pc.skyP.xyz), 0.0), 140.0);
                                col += vec3(1.0, 0.85, 0.60) * g * 1.6 * clamp(pc.skyP.y * 4.0, 0.0, 1.0);
                            }
                        } else {
                        // --- SSAO: 8 проб в полусфере нормали, радиус ~0.7 м, гаснет вдали ---
                        vec3 t1 = normalize(abs(N.y) < 0.9 ? cross(N, vec3(0.0, 1.0, 0.0))
                                                           : cross(N, vec3(1.0, 0.0, 0.0)));
                        vec3 t2 = cross(N, t1);
                        float occ = 0.0;
                        // M8.124b: вдали пробы SSAO субпиксельны и дребезжат — не считаем вовсе
                        // (вклад и так гаснет к 90 м, см. smoothstep ниже)
                        for (int i = 0; i < (P.z < 90.0 ? 8 : 0); i++) {
                            float a = 6.28318 * (float(i) + h0) * 0.125;
                            float r = 0.18 + 0.55 * fract(h0 * 7.13 + float(i) * 0.618);
                            vec3 sp = P + (t1 * cos(a) + t2 * sin(a)
                                           + N * (0.5 + 0.5 * fract(h0 * 3.7 + float(i) * 0.31))) * r;
                            if (sp.z < nr) continue;
                            vec2 uv = vec2(sp.x / (sp.z * tanXY.x), -sp.y / (sp.z * tanXY.y)) * 0.5 + 0.5;
                            if (uv.x <= 0.0 || uv.x >= 1.0 || uv.y <= 0.0 || uv.y >= 1.0) continue;
                            float dz = texelFetch(rasterDepth, ivec2(uv * res2), 0).r;
                            float vz = (nr * fr) / max(fr - dz * (fr - nr), 1e-6);
                            // ближе точки пробы, но не дальний план (иначе силуэты темнят фон)
                            if (vz < sp.z - 0.03 && sp.z - vz < 1.2) occ += 1.0;
                        }
                        col *= 1.0 - 0.5 * (occ * 0.125) * smoothstep(90.0, 30.0, P.z);
                        // --- контактная тень: марш к солнцу (~2.5 м); днём, солнце над горизонтом.
                        // M8.124b: ВДАЛИ НЕ МАРШИРУЕМ — шаги становятся субпиксельными, тест
                        // глубины дребезжит кадр к кадру, и дальние блоки мерцали (репорт).
                        if (pc.skyP.y > 0.03 && P.z < 48.0) {
                            vec3 rightv = -pc.left.xyz;
                            vec3 sv = vec3(dot(pc.skyP.xyz, rightv),
                                           dot(pc.skyP.xyz, pc.up.xyz),
                                           dot(pc.skyP.xyz, pc.forward.xyz));
                            float sh = 1.0;
                            for (int i = 1; i <= 12; i++) {
                                vec3 sp = P + N * 0.03 + sv * (0.20 * (float(i) - 0.5 + h0));
                                if (sp.z < nr) break;
                                vec2 uv = vec2(sp.x / (sp.z * tanXY.x), -sp.y / (sp.z * tanXY.y)) * 0.5 + 0.5;
                                if (uv.x <= 0.0 || uv.x >= 1.0 || uv.y <= 0.0 || uv.y >= 1.0) break;
                                float dz = texelFetch(rasterDepth, ivec2(uv * res2), 0).r;
                                float vz = (nr * fr) / max(fr - dz * (fr - nr), 1e-6);
                                if (vz < sp.z - 0.05 && sp.z - vz < 0.9) { sh = 0.55; break; }
                            }
                            // затухание к 48 м — совпадает с гейтом марша, шов не виден
                            col *= mix(1.0, sh, clamp(pc.skyP.y * 3.0, 0.0, 1.0)
                                                * smoothstep(48.0, 24.0, P.z));
                        }
                        }   // конец else (не вода): SSAO + контактная тень
                    }
                }

                // === M8.86 РАЗМЫТИЕ В ДВИЖЕНИИ (порт MOTION_BLUR) ===
                // ⚠️ РАЗМЫВАЕМ ДО ТОНМАППИНГА, в линейном HDR — так работает настоящий затвор: он
                // копит СВЕТ, а не готовые пиксели. Размажь после — и яркий след фонаря стал бы
                // грязно-серым вместо светящейся полосы.
                // Вектор движения у нас уже есть (его требует DLSS): это сдвиг пикселя В ПРОШЛЫЙ
                // кадр. Усредняем цвет вдоль него — ровно то, что видел бы затвор за время кадра.
                if (ex.mblur > 0.001 && !rasterIn && int(pc.obox[0].x + 0.5) == 0) {   // не мешаем отладке буферов (F6)
                    vec2 mv = imageLoad(gMotion, gpix).rg;              // в РЕНДЕРНЫХ пикселях
                    vec2 dirPx = mv * (srcRes / srcSize) * ex.mblur;    // -> в пиксели исходного образа
                    float len = length(dirPx);
                    float maxLen = 0.06 * srcRes.x;                     // потолок: 6% ширины кадра
                    if (len > maxLen) { dirPx *= maxLen / len; len = maxLen; }
                    if (len > 1.0) {                                    // меньше пикселя размывать нечего
                        vec3 sum = col; float cnt = 1.0;
                        for (int i = 1; i <= 7; i++) {
                            vec2 sp = vec2(spix) + dirPx * (float(i) / 7.0);
                            sum += imageLoad(hdrIn, ivec2(clamp(sp, vec2(0.0), srcRes - 1.0))).rgb;
                            cnt += 1.0;
                        }
                        col = sum / cnt;
                    }
                }

                // === M8.87 БЛИКИ ЛИНЗЫ ОТ СОЛНЦА И ЛУНЫ ===
                // Объектив — это стопка стёкол, и очень яркий источник в кадре переотражается между
                // ними: вокруг светила встаёт ореол, а по линии «светило — центр кадра» выстраиваются
                // «призраки» (отражения через центр). Считаем в ЛИНЕЙНОМ HDR, до тонмаппинга: тогда
                // автоэкспозиция сама пригасит блик днём, а ночью луна даст мягкое свечение.
                //
                // ⚠️ БЛИК ОТ СОЛНЦА, СПРЯТАННОГО ЗА ГОРОЙ, — ФАЛЬШЬ. Поэтому спрашиваем буфер глубины:
                // небо в точке светила или геометрия. Заодно блик мягко гаснет, когда солнце заходит
                // за край холма, — потому что видимость считается по четырём точкам, а не по одной.
                // M8.119: блик копим ОТДЕЛЬНО и добавляем ПОСЛЕ экспозиции кадра. Блик — свойство
                // линзы, его видимая яркость не должна зависеть от автоэкспозиции сцены: раньше
                // днём над водой (сцена яркая, экспозиция ~0.3) блик давился в ноль, а под водой
                // (темно, экспозиция ×4) — раздувался. Отсюда же «слепящая луна» из M8.108.
                vec3 flareC = vec3(0.0);
                if (ex.flare > 0.001 && !rasterIn && int(pc.obox[0].x + 0.5) == 0) {
                    vec2 res = vec2(float(width), float(height));
                    vec2 cpix = vec2(pix) + 0.5;
                    for (int b = 0; b < 2; b++) {
                        vec3 bd = (b == 0) ? pc.skyP.xyz : -pc.skyP.xyz;   // луна ходит напротив солнца
                        float fwd = dot(bd, pc.forward.xyz);
                        if (fwd < 0.10) continue;                          // светило за спиной
                        // M8.108: светило ПОД ГОРИЗОНТОМ не бликует. Ночью солнце внизу, и глядя
                        // под ноги, мы имели fwd>0 — солнце слепило СКВОЗЬ ПЛАНЕТУ (скрин).
                        if (bd.y < -0.02) continue;
                        // проекция светила на экран — ТЕМ ЖЕ базисом, каким строятся первичные лучи
                        vec3 rightv = -pc.left.xyz;
                        float su = (dot(bd, rightv) / fwd) / pc.left.w;    // left.w = tanX
                        float sv = (dot(bd, pc.up.xyz) / fwd) / pc.up.w;   // up.w   = tanY
                        vec2 spos = vec2((su * 0.5 + 0.5) * res.x, (0.5 - sv * 0.5) * res.y);
                        if (spos.x < 0.0 || spos.x >= res.x || spos.y < 0.0 || spos.y >= res.y) continue;

                        // ⚠️ ВИДИМОСТЬ — ЭТО ДОЛЯ ДИСКА, А НЕ ЛОГИЧЕСКОЕ «ДА/НЕТ». Сначала я брал четыре
                        // пробы в пяти пикселях от центра светила: солнце, наполовину закопанное в
                        // холм, попадало этими пробами в небо и считалось видимым ЦЕЛИКОМ — ореол
                        // разливался по склону. Теперь опрашиваем ДИСК размером с само светило и
                        // берём долю проб, попавших в небо.
                        // ⚠️ ЦЕНТР СВЕТИЛА — ГЛАВНАЯ ПРОБА. Закрыт центр — светило закрыто, блика нет.
                        // Раньше я опрашивал диск радиусом 2% высоты кадра (это ~21 пиксель), а сам
                        // диск солнца занимает около восьми: пробы улетали ВЫШЕ ГРЕБНЯ, попадали в
                        // небо, и солнце, севшее за хребет, считалось частично видимым — зарево
                        // ложилось поверх тёмной долины.
                        // M8.108: небо в guide-глубине = РОВНО 1.0 (NDC-глубина, ~1-near/vz), а
                        // порог 0.9995 считал «небом» всю землю дальше ~100 блоков — блик проходил
                        // окклюзию, глядя в грунт с высоты. Порог вплотную к единице.
                        if (imageLoad(gDepth, ivec2(spos * srcSize / res)).r < 0.999999) continue;

                        float vis = 0.0;
                        const int VT = 13;
                        for (int k = 0; k < VT; k++) {
                            float a = float(k) * 2.39996;                  // золотой угол — ровная спираль
                            float r = sqrt(float(k) / float(VT)) * res.y * 0.007;   // диск размером с солнце
                            vec2 sp = clamp(spos + vec2(cos(a), sin(a)) * r, vec2(0.0), res - 1.0);
                            vis += (imageLoad(gDepth, ivec2(sp * srcSize / res)).r >= 0.999999) ? 1.0 : 0.0;
                        }
                        vis /= float(VT);
                        // Квадрат: краешек солнца из-за холма даёт не четверть блика, а шестнадцатую.
                        vis *= vis;
                        if (vis <= 0.003) continue;

                        // ⚠️ БЛИК РАЗГОРАЕТСЯ, КОГДА СМОТРИШЬ НА СВЕТИЛО, А НЕ ПРОСТО КОГДА ОНО В КАДРЕ.
                        // Сначала я давал полную силу любому светилу, попавшему в кадр, — и солнце,
                        // болтающееся у верхнего края, слепило «призраком» на траве. У настоящего
                        // объектива переотражения растут, когда источник идёт к оси взгляда: fwd — это
                        // косинус угла между взглядом и светилом (1 = точно в центре кадра).
                        vis *= smoothstep(0.55, 0.96, fwd);
                        if (vis <= 0.001) continue;

                        vec3 tint = (b == 0) ? vec3(1.00, 0.92, 0.78) : vec3(0.62, 0.74, 1.00);
                        vec2 p = (cpix - spos) / res.y;                    // всё меряем в долях ВЫСОТЫ кадра
                        float d = length(p);
                        vec3 f = tint * exp(-d * d * 160.0) * 0.22;        // ореол вплотную к светилу
                        f += tint * exp(-d * 8.0) * 0.012;                 // мягкое гало вдаль
                        f += tint * exp(-abs(p.y) * 240.0) * exp(-abs(p.x) * 3.0) * 0.055;  // штрих по горизонтали

                        // ПРИЗРАКИ: отражения светила ЧЕРЕЗ ЦЕНТР кадра, разного размера и цвета
                        vec2 cc = (cpix - res * 0.5) / res.y;
                        vec2 sc = (spos - res * 0.5) / res.y;
                        for (int g = 0; g < 4; g++) {
                            float scale = (g == 0) ? -0.35 : (g == 1) ? 0.45 : (g == 2) ? 0.80 : 1.30;
                            float rad   = (g == 0) ?  0.055 : (g == 1) ? 0.030 : (g == 2) ? 0.070 : 0.045;
                            vec3  gt    = (g == 0) ? vec3(0.70, 0.95, 0.60) : (g == 1) ? vec3(1.00, 0.60, 0.40)
                                        : (g == 2) ? vec3(0.50, 0.70, 1.00) : vec3(0.90, 0.55, 0.95);
                            f += gt * smoothstep(rad, 0.0, length(cc + sc * scale)) * 0.045;
                        }
                        flareC += f * vis * ex.flare * ((b == 0) ? 1.1 : 0.25);   // луна много тусклее солнца
                    }
                }

                // === ОТЛАДКА GUIDE-БУФЕРОВ (F6) — проверка глазами, что DLSS скормят правду ===
                int dbg = int(pc.obox[0].x + 0.5);
                // M8.124b: F6 в ФОЛБЭКЕ — проверка маски воды и глубин ЦИФРАМИ, не на глаз:
                // 1 = линейная глубина (ближнее светлое), 2 = маска (белым — за прозрачным
                // слоем, тёмно-красным — нет), 3+ = обычный кадр.
                if (dbg > 0 && dbg <= 2 && rasterIn) {
                    float d0v = texelFetch(rasterDepth, pix, 0).r;
                    float dSv = texelFetch(solidDepth, pix, 0).r;
                    vec3 dd;
                    if (dbg == 1) {
                        float nr2 = pc.obox[0].y, fr2 = pc.obox[0].z;
                        float lin = (nr2 * fr2) / max(fr2 - d0v * (fr2 - nr2), 1e-6);
                        dd = vec3(clamp(1.0 - lin / 64.0, 0.0, 1.0));
                    } else {
                        dd = (dSv > d0v + 1e-7) ? vec3(1.0) : vec3(0.25, 0.05, 0.05);
                    }
                    imageStore(ldrOut, pix, vec4(clamp(dd, 0.0, 1.0), 1.0));
                    return;
                }
                if (dbg > 0 && !rasterIn) {
                    // ⚠️ ЧИТАЕМ по gpix (рендерное разрешение), а ПИШЕМ по pix (экранное) — иначе
                    // при включённом апскейле картинка уехала бы в угол.
                    vec3 d;
                    if (dbg == 1) {
                        // ⚠️ NDC-глубину нельзя смотреть «как есть»: при near=0.05 она почти вся
                        // лежит в 0.99..1.0, и экран выходит СПЛОШЬ ЧЁРНЫМ. Раскручиваем её обратно
                        // в МЕТРЫ (обратная проекция) и показываем линейно: ближнее светлое, небо чёрное.
                        float near = pc.obox[0].y, far = pc.obox[0].z;
                        float z = imageLoad(gDepth, gpix).r;
                        float lin = (near * far) / max(far - z * (far - near), 1e-6);
                        d = vec3(clamp(1.0 - lin / 64.0, 0.0, 1.0));  // 0 блоков = белое, 64+ = чёрное
                    } else if (dbg == 2) {                            // движение: вправо=красный, вниз=зелёный
                        vec2 mv = imageLoad(gMotion, gpix).rg;
                        d = vec3(0.5) + vec3(mv.x, mv.y, 0.0) * 0.05;
                    } else if (dbg == 3) {                            // нормаль
                        d = imageLoad(gNormal, gpix).rgb * 0.5 + 0.5;
                    } else if (dbg == 4) {                            // диффузное альбедо
                        d = pow(imageLoad(gDiff, gpix).rgb, vec3(1.0/2.2));
                    } else if (dbg == 5) {                            // зеркальное альбедо (F0)
                        d = pow(imageLoad(gSpec, gpix).rgb, vec3(1.0/2.2));
                    } else {                                          // шероховатость
                        d = vec3(imageLoad(gNormal, gpix).a);
                    }
                    imageStore(ldrOut, pix, vec4(clamp(d, 0.0, 1.0), 1.0));
                    return;
                }

                float u = ((float(pix.x)+0.5)/float(width))*2.0-1.0;
                float v = 1.0 - ((float(pix.y)+0.5)/float(height))*2.0;
                float vignette = sqrt(clamp(dot(vec2(u,v), vec2(u,v)) * 0.5, 0.0, 1.0));

                // === ЭКРАННЫЕ ЭФФЕКТЫ (перенесены из RT-шейдера: DLSS не должен их «денойзить») ===
                // ГОРИМ — ванильный fire overlay: языки пламени снизу экрана
                if (pc.outlineInfo.y > 0.001) {
                    // M8.123c: params.y — ТИКИ (20/с), а формула писалась под СЕКУНДЫ: пламя
                    // неслось в 20 раз быстрее задуманного (знакомая ловушка frameTimeCounter).
                    float fireT = pc.params.y / 20.0;
                    float fy    = v * 0.5 + 0.5;
                    float flame = snoise(vec2(u * 3.0, fy * 2.6 - fireT * 1.8)) * 0.5 + 0.5;
                    float mask  = smoothstep(0.62, 0.0, fy) * flame;
                    col = mix(col, vec3(1.7, 0.55, 0.10), clamp(mask, 0.0, 1.0) * pc.outlineInfo.y * 0.80);
                    col += vec3(0.12, 0.035, 0.0) * pc.outlineInfo.y;
                }
                // ЯД — зеленоватый тинт
                if (pc.fx1.z > 0.001)
                    col = mix(col, col * vec3(0.55, 1.05, 0.45), pc.fx1.z * 0.75);
                // ИССУШЕНИЕ — тёмный и обесцвеченный
                if (pc.fx1.w > 0.001) {
                    float g = dot(col, vec3(0.2126, 0.7152, 0.0722));
                    col = mix(col, mix(vec3(g), col, 0.35) * vec3(0.45, 0.40, 0.48), pc.fx1.w * 0.8);
                }
                // === НИЗКОЕ HP / ПОЛУЧЕННЫЙ УРОН — наша сборка (clean-room M8.102) ===
                // Принцип: при низком HP кадр обесцвечивается к люме, а кромка экрана
                // подкрашивает люму красным (сила растёт от «трёх сердец» к «одному»).
                // Разовый урон — красная вспышка люмы: слабый лишь по кромке, критический
                // заливает весь кадр (обе — с корнем, чтобы вспышка гасла мягко).
                float hpLow1 = pc.fx2.z, hpLow3 = pc.fx2.w;
                float dmgMinor = pc.fx3.x, dmgCrit = pc.fx3.y;
                float luma709 = dot(col, vec3(0.2126, 0.7152, 0.0722));
                vec3 flashRed = vec3(luma709, 0.0, 0.0);
                if (hpLow3 > 0.001 || hpLow1 > 0.001) {
                    vec3 grayToRed = mix(vec3(luma709), luma709 * vec3(1.0, 0.3, 0.3), vignette);
                    col = mix(col, grayToRed, mix(vignette * hpLow3, hpLow1, hpLow1));
                }
                if (dmgMinor > 0.001) col = mix(col, flashRed, vignette * sqrt(min(dmgMinor, 1.0)));
                if (dmgCrit  > 0.001) col = mix(col, flashRed, sqrt(min(dmgCrit, 1.0)));

                // --- ЗАМЕР ЯРКОСТИ (по сетке, каждый 8-й пиксель — этого хватает с запасом) ---
                // ⚠️ Меряем СЫРУЮ сцену, ДО применения экспозиции: иначе получилась бы обратная
                // связь — экспозиция подстраивалась бы под саму себя и «поплыла».
                if (!rasterIn && (pix.x & 7) == 0 && (pix.y & 7) == 0) {
                    float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
                    float lg  = clamp(log2(max(lum, 1e-5)) + 16.0, 0.0, 32.0);   // сдвиг: atomic только для uint
                    atomicAdd(ex.accum, uint(lg * 256.0));
                    atomicAdd(ex.count, 1u);
                }

                // Растр: яркость уже задана игрой, автоэкспозиция не работает (замера нет) —
                // остаётся только ручной сдвиг из настроек.
                col *= (rasterIn ? 1.0 : ex.exposure) * exp2(ex.expBias);   // экспозиция кадра + сдвиг из настроек (стопы)
                // M8.140: ПОДВОДНАЯ НОЧЬ — мрачнее. Автоэкспозиция раздувала тёмную подводную
                // ночь до дневной яркости (репорт). Гасим ПОСЛЕ экспозиции (замер сцены не
                // трогаем -> обратной связи нет): под водой + ночь -> сцена уходит в мрак.
                if (pc.params.x > 0.5) {
                    float night = smoothstep(0.06, -0.14, pc.skyP.y);   // 0 день .. 1 ночь (skyP.y = высота солнца)
                    col *= mix(1.0, 0.40, night);
                }
                col = ToneMap_AgX(col);
                col = pow(col, vec3(1.0/2.2));
                // M8.120: блик — в ДИСПЛЕЙНОМ пространстве (после AgX и гаммы), как зерно и
                // виньетка. В M8.119 я ставил его до AgX — но возле солнца кадр уже у белой
                // точки, и ПЛЕЧО тонмаппера съедало добавку: призраки по 0.012 поверх 0.9
                // не видны вовсе. Здесь 0.05 — это честные 5% яркости экрана, всегда.
                col += flareC;

                // === M8.86 ГРЕЙД И ЗЕРНО (наши реализации; имена настроек — классика шейдер-паков) ===
                // Всё это — уже ПОСЛЕ тонмаппинга: это свойства плёнки и линзы, а не света в сцене.
                if (abs(ex.sat - 1.0) > 0.001) {
                    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
                    col = max(mix(vec3(luma), col, ex.sat), vec3(0.0));
                }
                if (ex.grain > 0.001) {
                    // Зерно заметно в тенях и почти не видно в светах — как на плёнке.
                    float n = fract(sin(dot(vec2(pix) + pc.params.y, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
                    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
                    col += n * ex.grain * 0.09 * (1.0 - luma * 0.7);
                }
                if (ex.vign > 0.001)
                    col *= 1.0 - ex.vign * 0.85 * pow(vignette, 2.2);   // vignette: 0 в центре, 1 по углам

                imageStore(ldrOut, pix, vec4(clamp(col, 0.0, 1.0), 1.0));
            }
            """;

    /**
     * Настройки страницы «Изображение» -> в буфер экспозиции (шейдер читает их оттуда).
     * Проценты из UI переводим в те единицы, в которых думает шейдер.
     */
    // M8.122: этот кадр — растровый фолбэк? (выставляет recordRaster, сбрасывает record)
    private boolean rasterFrame = false;

    private void writeSettings() {
        var c = Initializer.CONFIG;
        expMap.putFloat(16, c.motionBlur / 100f)          // доля вектора движения
              .putFloat(20, c.vignette / 100f)
              .putFloat(24, c.filmGrain / 100f)
              .putFloat(28, c.chromatic / 100f)
              .putFloat(32, c.exposureBias / 10f)         // -20..20 -> -2..+2 стопа
              .putFloat(36, c.saturation / 100f)
              .putFloat(40, c.lensFlare / 100f)
              .putFloat(44, rasterFrame ? 1f : 0f);       // M8.122: режим растрового входа
    }

    /** Режим отладочного просмотра guide-буферов (F6): 0 = обычная картинка, 1..6 = буферы. */
    public static int debugView = 0;
    public static final int DEBUG_MODES = 7;

    /**
     * «Зрачок»: один поток сводит замеры кадра в экспозицию и плавно подводит её к цели.
     * Отдельным проходом, потому что делать это надо РОВНО ОДИН РАЗ за кадр, а пост-шейдер
     * выполняется миллион раз.
     */
    private static final String EXP_SRC = """
            #version 460
            layout(local_size_x = 1) in;

            layout(std430, binding = 0) buffer Exposure {
                uint  accum;
                uint  count;
                float exposure;
                float pad;
            } ex;

            layout(push_constant) uniform Push {
                vec4 p;   // x = время кадра (сек)
            } pc;

            void main() {
                if (ex.count == 0u) return;

                float avgLog = float(ex.accum) / float(ex.count) / 256.0 - 16.0;
                float avgLum = exp2(avgLog);

                const float KEY = 0.13;              // «средне-серый» — к нему и подводим кадр
                // ⚠️ ПОТОЛОК 4.0 БЫЛ СЛИШКОМ ЩЕДРЫМ: ночь и глубина воды вытягивались почти до
                // дневной яркости, и разницы между днём и ночью не оставалось вовсе. Глаз тоже не
                // всесилен — тёмное должно остаться тёмным, просто читаемым.
                float target = clamp(KEY / max(avgLum, 1e-4), 0.30, 2.2);

                // ⚠️ К ТЕМНОТЕ ГЛАЗ ПРИВЫКАЕТ МЕДЛЕННЕЕ, ЧЕМ К СВЕТУ — так и делаем: рост экспозиции
                // (вход в пещеру, ночь) тянется, падение (выход на солнце) быстрое. Симметричная
                // адаптация выглядит неестественно и «дышит» на каждый поворот головы.
                float speed = (target < ex.exposure) ? 2.2 : 0.55;   // 1/сек
                float dt = clamp(pc.p.x, 0.0, 0.25);
                ex.exposure = mix(ex.exposure, target, 1.0 - exp(-dt * speed));

                ex.accum = 0u;
                ex.count = 0u;
            }
            """;

    private static RtPost INSTANCE;
    private static boolean failed = false;

    private static final int IMG_BINDINGS = 7;   // 0 hdr, 1 ldr, 2..6 guide (для отладки)
    private static final int BINDINGS = 10;      // + 7 экспозиция, 8 растровая глубина (M8.123), 9 глубина ТВЕРДИ (M8.124)

    private final long descLayout, descPool, pipelineLayout, pipeline;
    private final long expLayout, expPool, expPipeLayout, expPipeline;
    private final long expSet;
    private final AccelStruct.RawBuffer expBuf;   // {accum, count, exposure, pad, + настройки}
    private ByteBuffer expMap;                    // тот же буфер, отображённый в память
    private final long[] descSets = new long[RtSnapshot.FRAMES];
    private int frameIdx = 0;
    private long lastFrameNs = 0;
    private RtImage hdr, ldr;
    private RtImage depth, motion, normal, diffAlb, specAlb, react;   // guide-буферы для DLSS
    private RtImage upscaled;                                  // выход Ray Reconstruction (полное разрешение)
    // M8.123: глубина растра для фолбэка (SSAO/контактные тени). В RT-кадрах биндится заглушка —
    // дескриптор статически используется шейдером и обязан быть валидным, даже если ветка не идёт.
    private final long depthSampler;
    private final RtImage dummyDepth;
    private long rasterDepthView = 0;   // view глубины свопчейна; ставит recordRaster на свой кадр
    // M8.124: снимок глубины ДО прозрачного слоя (твердь). Разница с финальной глубиной =
    // маска воды + её толщина для поглощения — вода в стиле Iris без второго рендера мира.
    private net.vulkanmod.vulkan.texture.VulkanImage solidDepth;

    private RtPost() {
        VkDevice device = Vulkan.getVkDevice();
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(BINDINGS, stack);
            for (int i = 0; i < IMG_BINDINGS; i++)
                binds.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            binds.get(7).binding(7).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            // M8.123: глубина растра (фолбэк «Шейдеры без RT») — сэмплером, это depth-формат
            binds.get(8).binding(8).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            // M8.124: глубина ТВЕРДИ (до прозрачного слоя) — маска и толщина воды в фолбэке
            binds.get(9).binding(9).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo dslInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, dslInfo, null, p), "post dsl");
            descLayout = p.get(0);

            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(3, stack);
            ps.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(IMG_BINDINGS * RtSnapshot.FRAMES);
            ps.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(RtSnapshot.FRAMES);
            ps.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(RtSnapshot.FRAMES * 2);
            VkDescriptorPoolCreateInfo dp = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().pPoolSizes(ps).maxSets(RtSnapshot.FRAMES);
            check(vkCreateDescriptorPool(device, dp, null, p), "post pool");
            descPool = p.get(0);

            for (int f = 0; f < RtSnapshot.FRAMES; f++) {
                VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default().descriptorPool(descPool).pSetLayouts(stack.longs(descLayout));
                LongBuffer pSet = stack.mallocLong(1);
                check(vkAllocateDescriptorSets(device, ai, pSet), "post set");
                descSets[f] = pSet.get(0);
            }

            long module = RtSnapshot.compileCompute(device, SRC);
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(256);
            VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(descLayout)).pPushConstantRanges(pcr);
            check(vkCreatePipelineLayout(device, plInfo, null, p), "post layout");
            pipelineLayout = p.get(0);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, cpci, null, p), "post pipeline");
            pipeline = p.get(0);
            vkDestroyShaderModule(device, module, null);

            // --- буфер экспозиции: host-visible, чтобы задать стартовое значение без лишних команд ---
            expBuf = AccelStruct.createBuffer(64, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, false);
            PointerBuffer pMap = stack.mallocPointer(1);
            check(vkMapMemory(device, expBuf.memory, 0, 64, 0, pMap), "map exposure");
            // ⚠️ ОТОБРАЖЕНИЕ ДЕРЖИМ ПОСТОЯННО. Раньше буфер тут же отсоединялся (vkUnmapMemory) —
            // он был нужен один раз, задать стартовую экспозицию. Теперь в него каждый кадр едут
            // настройки изображения, и отсоединить его = писать по мёртвому указателю.
            ByteBuffer eb = MemoryUtil.memByteBuffer(pMap.get(0), 64);
            eb.putInt(0, 0).putInt(4, 0).putFloat(8, 1.0f).putFloat(12, 0f);   // exposure стартует с 1
            expMap = eb;   // сюда же кладём настройки изображения (см. writeSettings)

            // --- «зрачок»: отдельный проход, один поток на кадр ---
            VkDescriptorSetLayoutBinding.Buffer eb2 = VkDescriptorSetLayoutBinding.calloc(1, stack);
            eb2.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo edsl = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(eb2);
            check(vkCreateDescriptorSetLayout(device, edsl, null, p), "exp dsl");
            expLayout = p.get(0);

            VkDescriptorPoolSize.Buffer eps = VkDescriptorPoolSize.calloc(1, stack);
            eps.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            VkDescriptorPoolCreateInfo edp = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().pPoolSizes(eps).maxSets(1);
            check(vkCreateDescriptorPool(device, edp, null, p), "exp pool");
            expPool = p.get(0);

            VkDescriptorSetAllocateInfo eai = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(expPool).pSetLayouts(stack.longs(expLayout));
            check(vkAllocateDescriptorSets(device, eai, p), "exp set");
            expSet = p.get(0);

            VkDescriptorBufferInfo.Buffer ebi = VkDescriptorBufferInfo.calloc(1, stack);
            ebi.get(0).buffer(expBuf.buffer).offset(0).range(VK_WHOLE_SIZE);
            VkWriteDescriptorSet.Buffer ew = VkWriteDescriptorSet.calloc(1, stack);
            ew.get(0).sType$Default().dstSet(expSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(ebi);
            vkUpdateDescriptorSets(device, ew, null);

            // M8.123: сэмплер глубины растра (nearest, clamp) + заглушка 1x1 для RT-кадров
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxAnisotropy(1.0f);
            check(vkCreateSampler(device, sci, null, p), "depth sampler");
            depthSampler = p.get(0);
            dummyDepth = new RtImage(1, 1, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_SAMPLED_BIT);

            long emod = RtSnapshot.compileCompute(device, EXP_SRC);
            VkPushConstantRange.Buffer epcr = VkPushConstantRange.calloc(1, stack);
            epcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            VkPipelineLayoutCreateInfo epl = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(expLayout)).pPushConstantRanges(epcr);
            check(vkCreatePipelineLayout(device, epl, null, p), "exp layout");
            expPipeLayout = p.get(0);

            VkPipelineShaderStageCreateInfo estage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(emod).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer ecpci = VkComputePipelineCreateInfo.calloc(1, stack);
            ecpci.get(0).sType$Default().stage(estage).layout(expPipeLayout);
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, ecpci, null, p), "exp pipeline");
            expPipeline = p.get(0);
            vkDestroyShaderModule(device, emod, null);
        }
        Initializer.LOGGER.info("[RT] RtPost готов — тонмаппинг и автоэкспозиция вынесены из трассировки.");
    }

    // Разрешения: трассировка и guide живут в РЕНДЕРНОМ (низком), а выход DLSS и экран — в ПОЛНОМ.
    private int outW, outH;

    private void ensureImages(int w, int h, int ow, int oh) {
        if (hdr != null && hdr.width == w && hdr.height == h && outW == ow && outH == oh) return;
        for (RtImage img : new RtImage[]{hdr, ldr, depth, motion, normal, diffAlb, specAlb, react, upscaled})
            if (img != null) img.free();
        outW = ow; outH = oh;
        int st = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        // TRANSFER_DST: в растровом фолбэке (M8.122) сюда БЛИТИТСЯ ванильный кадр со свопчейна.
        hdr = new RtImage(w, h, VK_FORMAT_R16G16B16A16_SFLOAT,
                st | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        depth   = new RtImage(w, h, VK_FORMAT_R32_SFLOAT,          st);
        motion  = new RtImage(w, h, VK_FORMAT_R16G16_SFLOAT,       st);
        normal  = new RtImage(w, h, VK_FORMAT_R16G16B16A16_SFLOAT, st);
        diffAlb = new RtImage(w, h, VK_FORMAT_R16G16B16A16_SFLOAT, st);
        specAlb = new RtImage(w, h, VK_FORMAT_R16G16B16A16_SFLOAT, st);
        react   = new RtImage(w, h, VK_FORMAT_R16_SFLOAT,           st);   // маска «не накапливать»
        // выход Ray Reconstruction — чистый кадр в ПОЛНОМ разрешении
        upscaled = new RtImage(ow, oh, VK_FORMAT_R16G16B16A16_SFLOAT, st | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        // ⚠️ R8G8B8A8, не B8G8R8A8: GLSL не умеет объявлять bgra8 storage image. Перестановку
        // каналов в BGRA-свопчейн сделает сам vkCmdBlitImage (он конвертирует форматы).
        ldr = new RtImage(ow, oh, VK_FORMAT_R8G8B8A8_UNORM, st | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
    }

    /** HDR-образ, в который пишет трассировка (он же вход DLSS). */
    public static RtImage hdrImage(int w, int h, int outW, int outH) {
        if (failed) return null;
        try {
            if (INSTANCE == null) INSTANCE = new RtPost();
            INSTANCE.ensureImages(w, h, outW, outH);
            return INSTANCE.hdr;
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] RtPost init failed: ", t);
            return null;
        }
    }

    /** Куда Ray Reconstruction кладёт чистый кадр (полное разрешение). */
    public static RtImage upscaledImage() { return INSTANCE != null ? INSTANCE.upscaled : null; }

    public static RtImage ldrImage() { return INSTANCE != null ? INSTANCE.ldr : null; }

    /** Guide-буферы (в порядке привязок 14..19 у трассировки). null, пока пост не создан. */
    public static RtImage[] guides() {
        if (INSTANCE == null || INSTANCE.depth == null) return null;
        return new RtImage[]{INSTANCE.depth, INSTANCE.motion, INSTANCE.normal,
                             INSTANCE.diffAlb, INSTANCE.specAlb, INSTANCE.react};
    }

    /**
     * Записать пост-проход в кадровый буфер. push — тот же 256-байтный блок, что у трассировки.
     * dlssDone — кадр уже прошёл Ray Reconstruction, значит цвет берём из её выхода (полное
     * разрешение), а не из шумного HDR.
     */
    public static void record(VkCommandBuffer cmd, ByteBuffer push, boolean dlssDone) {
        if (INSTANCE == null || failed || INSTANCE.hdr == null) return;
        try {
            INSTANCE.rasterFrame = false;   // M8.122: RT-кадр, не растровый фолбэк
            INSTANCE.recordImpl(cmd, push, dlssDone);
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] RtPost record failed: ", t);
        }
    }

    /**
     * M8.122: РАСТРОВЫЙ ФОЛБЭК («Шейдеры» ВКЛ, трассировка ВЫКЛ). Ванильный растр уже лежит в
     * цветовом вложении свопчейна: забираем его блитом в hdr-образ (блит сам конвертирует
     * BGRA-свопчейн в RGBA16F), прогоняем НАШ пост (AgX + экранные эффекты; размытие, блик и
     * автоэкспозиция в этом режиме выключены — их guide-данные без трассировки — мусор) и
     * оставляем результат в ldr: дальше RtScreen.composite блитит его на экран, как RT-кадр.
     * «Рендерное» и «экранное» разрешения равны — растр уже в полном размере.
     */
    /**
     * M8.124: из DefaultMainPass ПЕРЕД translucent-слоем (вне прохода): снимок глубины ТВЕРДИ.
     * Пиксели, где финальная глубина ближе снимка, закрыты прозрачным слоем — это маска
     * воды/стекла, а разница глубин = толщина воды (поглощение). Копия depth->depth тем же
     * форматом (blit depth в цвет запрещён Vulkan-ом).
     */
    public static void copySolidDepth(VkCommandBuffer cmd, net.vulkanmod.vulkan.texture.VulkanImage depthAtt) {
        if (failed || depthAtt == null) return;
        try {
            if (INSTANCE == null) INSTANCE = new RtPost();
            RtPost p = INSTANCE;
            if (p.solidDepth == null || p.solidDepth.width != depthAtt.width || p.solidDepth.height != depthAtt.height) {
                if (p.solidDepth != null) p.solidDepth.free();
                p.solidDepth = net.vulkanmod.vulkan.texture.VulkanImage.builder(depthAtt.width, depthAtt.height)
                        .setName("RT solid depth")
                        .setFormat(depthAtt.format)
                        .setUsage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                        .setClamp(true)
                        .createVulkanImage();
                Initializer.LOGGER.info("[RT] фолбэк: снимок глубины тверди {}x{} (формат {})",
                        depthAtt.width, depthAtt.height, depthAtt.format);
            }
            try (MemoryStack stack = stackPush()) {
                depthAtt.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                p.solidDepth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
                region.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.get(0).extent().set(depthAtt.width, depthAtt.height, 1);
                vkCmdCopyImage(cmd, depthAtt.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        p.solidDepth.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                p.solidDepth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                // глубину — обратно вложением: translucent-слой сейчас будет в неё писать
                depthAtt.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] снимок глубины тверди: ", t);
        }
    }

    public static boolean recordRaster(VkCommandBuffer cmd, net.vulkanmod.vulkan.texture.VulkanImage color,
                                       net.vulkanmod.vulkan.texture.VulkanImage depthAtt) {
        if (failed || color == null) return false;
        try {
            if (INSTANCE == null) INSTANCE = new RtPost();
            INSTANCE.ensureImages(color.width, color.height, color.width, color.height);
            INSTANCE.rasterFrame = true;
            try (MemoryStack stack = stackPush()) {
                // M8.123: глубина растра — в шейдер (SSAO + контактные тени)
                if (depthAtt != null) {
                    depthAtt.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    INSTANCE.rasterDepthView = depthAtt.getImageView();
                } else INSTANCE.rasterDepthView = 0;
                color.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                INSTANCE.hdr.transition(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
                blit.srcOffsets(1, VkOffset3D.calloc(stack).set(color.width, color.height, 1));
                blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                blit.dstOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
                blit.dstOffsets(1, VkOffset3D.calloc(stack).set(INSTANCE.hdr.width, INSTANCE.hdr.height, 1));
                blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                vkCmdBlitImage(cmd, color.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        INSTANCE.hdr.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_NEAREST);
                // hdr: запись блитом -> чтение компьютом (recordImpl добавит свой барьер, он
                // избыточен, но безвреден: порядок transfer -> compute уже установлен здесь)
                INSTANCE.hdr.transition(stack, cmd, VK_IMAGE_LAYOUT_GENERAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                ByteBuffer push = stack.calloc(256);
                RtSnapshot.fillRasterPush(push, color.width, color.height);
                INSTANCE.recordImpl(cmd, push, false);
                // глубину — обратно вложением: aux-проход GUI грузит её (LOAD)
                if (depthAtt != null)
                    depthAtt.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            Initializer.LOGGER.error("[RT] растровый пост: ", t);
            return false;
        } finally {
            if (INSTANCE != null) INSTANCE.rasterFrame = false;
        }
    }

    private void recordImpl(VkCommandBuffer cmd, ByteBuffer push, boolean dlssDone) {
        VkDevice device = Vulkan.getVkDevice();
        try (MemoryStack stack = stackPush()) {
            frameIdx = (frameIdx + 1) % RtSnapshot.FRAMES;
            long set = descSets[frameIdx];

            RtImage src = dlssDone ? upscaled : hdr;   // источник цвета для тонмаппинга

            // Источник и guide: запись (компьютом или DLSS) -> чтение компьютом поста
            for (RtImage img : new RtImage[]{src, depth, motion, normal, diffAlb, specAlb, react})
                img.transition(stack, cmd, VK_IMAGE_LAYOUT_GENERAL,
                        VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            ldr.transition(stack, cmd, VK_IMAGE_LAYOUT_GENERAL,
                    0, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            RtImage[] all = {src, ldr, depth, motion, normal, diffAlb, specAlb};
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(BINDINGS, stack);
            for (int i = 0; i < IMG_BINDINGS; i++) {
                VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack);
                ii.get(0).imageView(all[i].view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                w.get(i).sType$Default().dstSet(set).dstBinding(i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(ii);
            }
            VkDescriptorBufferInfo.Buffer ebi = VkDescriptorBufferInfo.calloc(1, stack);
            ebi.get(0).buffer(expBuf.buffer).offset(0).range(VK_WHOLE_SIZE);
            w.get(7).sType$Default().dstSet(set).dstBinding(7)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(ebi);

            // M8.123: binding 8 — глубина растра (фолбэк) или заглушка (RT-кадры). Заглушку
            // держим в SHADER_READ_ONLY барьером на месте (RtImage.transition следит за layout).
            boolean useRealDepth = rasterFrame && rasterDepthView != 0;
            if (!useRealDepth)
                dummyDepth.transition(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        0, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            VkDescriptorImageInfo.Buffer dpi = VkDescriptorImageInfo.calloc(1, stack);
            dpi.get(0).imageView(useRealDepth ? rasterDepthView : dummyDepth.view)
                    .sampler(depthSampler)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            w.get(8).sType$Default().dstSet(set).dstBinding(8)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(dpi);
            // M8.124: binding 9 — глубина ТВЕРДИ (снимок до translucent) или заглушка
            boolean useSolid = rasterFrame && solidDepth != null;
            VkDescriptorImageInfo.Buffer spi = VkDescriptorImageInfo.calloc(1, stack);
            spi.get(0).imageView(useSolid ? solidDepth.getImageView() : dummyDepth.view)
                    .sampler(depthSampler)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            w.get(9).sType$Default().dstSet(set).dstBinding(9)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(spi);
            vkUpdateDescriptorSets(device, w, null);

            writeSettings();   // ручки страницы «Изображение» -> в буфер, который читает шейдер

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(cmd, (ldr.width + 7) / 8, (ldr.height + 7) / 8, 1);

            // === «ЗРАЧОК»: сводим замеры кадра в экспозицию (один поток, один раз за кадр) ===
            // Барьер обязателен: пост НАКАПЛИВАЛ яркость атомарно, а этот проход её ЧИТАЕТ.
            VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack);
            mb.get(0).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, mb, null, null);

            long now = System.nanoTime();
            float dt = lastFrameNs == 0 ? 0.016f : (float) ((now - lastFrameNs) / 1e9);
            lastFrameNs = now;
            ByteBuffer epush = stack.malloc(16);
            epush.putFloat(0, dt).putFloat(4, 0f).putFloat(8, 0f).putFloat(12, 0f);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, expPipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, expPipeLayout, 0, stack.longs(expSet), null);
            vkCmdPushConstants(cmd, expPipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, epush);
            vkCmdDispatch(cmd, 1, 1, 1);

            // LDR: запись компьютом -> чтение блитом на свопчейн
            ldr.transition(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        }
    }

    public static void shutdown() {
        if (INSTANCE == null) return;
        try {
            VkDevice device = Vulkan.getVkDevice();
            vkDestroyPipeline(device, INSTANCE.pipeline, null);
            vkDestroyPipelineLayout(device, INSTANCE.pipelineLayout, null);
            vkDestroyDescriptorPool(device, INSTANCE.descPool, null);
            vkDestroyDescriptorSetLayout(device, INSTANCE.descLayout, null);
            vkDestroyPipeline(device, INSTANCE.expPipeline, null);
            vkDestroyPipelineLayout(device, INSTANCE.expPipeLayout, null);
            vkDestroyDescriptorPool(device, INSTANCE.expPool, null);
            vkDestroyDescriptorSetLayout(device, INSTANCE.expLayout, null);
            vkDestroySampler(device, INSTANCE.depthSampler, null);   // M8.123
            if (INSTANCE.dummyDepth != null) INSTANCE.dummyDepth.free();
            if (INSTANCE.solidDepth != null) INSTANCE.solidDepth.free();   // M8.124
            AccelStruct.destroyBuffer(INSTANCE.expBuf);
            for (RtImage img : new RtImage[]{INSTANCE.hdr, INSTANCE.ldr, INSTANCE.depth,
                    INSTANCE.motion, INSTANCE.normal, INSTANCE.diffAlb, INSTANCE.specAlb,
                    INSTANCE.react, INSTANCE.upscaled})
                if (img != null) img.free();
        } catch (Throwable t) { Initializer.LOGGER.error("[RT] RtPost shutdown", t); }
        INSTANCE = null;
    }

    private static void check(int r, String what) {
        if (r != VK_SUCCESS) throw new RuntimeException("[RT] Vulkan error in '" + what + "': " + r);
    }
}
