package net.vulkanmod.vulkan.shader.converter;

/**
 * НЕПАРНЫЙ ВХОД ФРАГМЕНТНОГО ШЕЙДЕРА (M8.67).
 *
 * В OpenGL фрагментный шейдер может объявить вход, которого вершинный не отдаёт: он просто
 * остаётся незаполненным, и программа работает. Наборы ресурсов этим пользуются: подменяют
 * фрагментный шейдер, а вершинный — нет (или подменяют оба, но игра берёт вершинный не оттуда).
 *
 * Vulkan так не умеет: у каждого входа должен быть свой location, парный выходу. Парсер, не найдя
 * пары, ВЫБРАСЫВАЛ объявление и шёл дальше — а использования в коде оставались, и компилятор падал
 * с «undeclared identifier». Игра при этом не запускалась ВООБЩЕ: на паках Fresh Food / Fresh Music
 * Discs, где во фрагментный шейдер предметов добавлены свои переменные.
 *
 * Теперь такой вход становится обычной переменной с нейтральным значением: шейдер компилируется,
 * игра запускается, а эффект пака просто не применяется (ровно как в OpenGL, где переменная
 * оставалась незаполненной).
 */
public class DefaultedInput implements GLSLParser.Node {

    private final String type;
    private final String id;

    public DefaultedInput(String type, String id) {
        this.type = type;
        this.id = id;
    }

    /** Нейтральное значение: для цветов — белый (умножение на него ничего не меняет), иначе ноль. */
    private String defaultValue() {
        return switch (this.type) {
            case "vec4"  -> "vec4(1.0)";
            case "vec3"  -> "vec3(1.0)";
            case "vec2"  -> "vec2(0.0)";
            case "float" -> "0.0";
            case "int"   -> "0";
            case "uint"  -> "0u";
            default      -> "%s(0)".formatted(this.type);
        };
    }

    @Override
    public String getStringValue() {
        return "%s %s = %s;   // вход без пары в вершинном шейдере — см. DefaultedInput\n"
                .formatted(this.type, this.id, this.defaultValue());
    }
}
