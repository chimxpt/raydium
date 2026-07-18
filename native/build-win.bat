@echo off
REM ============================================================================
REM  Сборка прослойки к NGX под Windows (mcrt_ngx.dll)
REM
REM  ⚠️ ТОЛЬКО x64. Библиотека nvsdk_ngx_d.lib — 64-битная; при сборке из 32-битной консоли
REM  линковщик не находит её символы (LNK2019 + предупреждение LNK4272 «x64 конфликтует с x86»).
REM  Скрипт сам переключается на x64, какую бы консоль ты ни открыл.
REM
REM  ЧТО НУЖНО:
REM    1. Build Tools for Visual Studio (компонент "C++ build tools")
REM    2. JAVA_HOME -> JDK 21
REM    3. Папка native\ целиком (вместе с deps\)
REM
REM  ЗАПУСК:  build-win.bat
REM ============================================================================

setlocal

if "%JAVA_HOME%"=="" (
    echo [!] Не задан JAVA_HOME. Пример:
    echo     set JAVA_HOME=C:\Program Files\Java\jdk-21
    exit /b 1
)

if not exist deps\DLSS\include\nvsdk_ngx.h (
    echo [!] Нет deps\DLSS — скопируй папку native\deps целиком с Linux-машины
    exit /b 1
)

REM --- Настроить окружение x64, если оно ещё не x64 ---
if /I not "%VSCMD_ARG_TGT_ARCH%"=="x64" (
    echo ^>^> переключаюсь на x64...
    set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
    if not exist "%VSWHERE%" (
        echo [!] Не нашёл vswhere.exe — открой "x64 Native Tools Command Prompt for VS" и запусти снова
        exit /b 1
    )
    for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSPATH=%%i"
    if not defined VSPATH (
        echo [!] Не нашёл C++ build tools — установи компонент "C++ build tools" в Visual Studio Installer
        exit /b 1
    )
    call "%VSPATH%\VC\Auxiliary\Build\vcvars64.bat" >nul
    if errorlevel 1 (
        echo [!] Не удалось настроить x64-окружение
        exit /b 1
    )
)

echo ^>^> целевая архитектура: %VSCMD_ARG_TGT_ARCH%
echo ^>^> собираю mcrt_ngx.dll...

REM ⚠️ /MD ОБЯЗАТЕЛЕН: nvsdk_ngx_d.lib собрана с ДИНАМИЧЕСКИМ CRT, а cl по умолчанию берёт
REM статический (/MT). Смешивать их нельзя — линковщик ругается «RuntimeLibrary mismatch:
REM MD_DynamicRelease не соответствует MT_StaticRelease».
REM
REM ⚠️ Системные библиотеки NGX за собой не тянет, а пользуется ими: реестр (advapi32),
REM окна (user32), перечисление устройств и проверка подписи драйвера (setupapi, crypt32).
cl /nologo /O2 /EHsc /LD /MD /std:c++17 ^
   /I "%JAVA_HOME%\include" /I "%JAVA_HOME%\include\win32" ^
   /I deps\Vulkan-Headers\include ^
   /I deps\DLSS\include ^
   mcrt_ngx.cpp ^
   deps\DLSS\lib\Windows_x86_64\x64\nvsdk_ngx_d.lib ^
   advapi32.lib user32.lib setupapi.lib crypt32.lib shell32.lib ole32.lib version.lib ^
   /link /MACHINE:X64 /OUT:mcrt_ngx.dll

if errorlevel 1 (
    echo.
    echo [!] Сборка не удалась
    exit /b 1
)

echo.
echo ^>^> ГОТОВО: mcrt_ngx.dll
echo.
echo Дальше:
echo   1. mcrt_ngx.dll  -^>  в jar мода, в папку  natives/  (рядом с libmcrt_ngx.so)
echo   2. deps\DLSS\lib\Windows_x86_64\rel\nvngx_dlssd.dll  -^>  в папку игры:  .minecraft\dlss\
echo.

endlocal
