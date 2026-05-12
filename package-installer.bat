@echo off
setlocal

REM ----------------------------------------------------------------------
REM Optional: build a Windows app-image (or installer) with jpackage.
REM
REM Default: --type app-image  → produces dist\LeoJasperService\
REM          (a folder containing the .exe + bundled JRE + jars)
REM
REM To produce a real installer, install WiX Toolset 3.x from
REM https://wixtoolset.org/ and switch PKG_TYPE below to "exe" or "msi".
REM ----------------------------------------------------------------------

set "JAVA_HOME=C:\Program Files\Java\jdk-26.0.1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "PROJECT_DIR=%~dp0"
set "DIST_DIR=%PROJECT_DIR%dist"

set "PKG_TYPE=app-image"
set "APP_NAME=LeoJasperService"
set "APP_VERSION=1.0.0"
set "MAIN_JAR=leo-jasper-rest.jar"
set "MAIN_CLASS=org.springframework.boot.loader.launch.JarLauncher"

if not exist "%JAVA_HOME%\bin\jpackage.exe" (
    echo [ERROR] jpackage not found. JDK 17+ required.
    exit /b 1
)

REM Build first.
call "%PROJECT_DIR%build.bat" -DskipTests package
if errorlevel 1 exit /b %ERRORLEVEL%

REM jpackage needs the input dir to contain ONLY the main jar — copy to a
REM scratch folder so we don't drag *.original etc. along.
set "STAGING=%DIST_DIR%\jpackage-input"
if exist "%STAGING%" rmdir /s /q "%STAGING%"
mkdir "%STAGING%"
copy /Y "%PROJECT_DIR%leo-jasper-rest\target\%MAIN_JAR%" "%STAGING%\" >nul

if exist "%DIST_DIR%\%APP_NAME%" rmdir /s /q "%DIST_DIR%\%APP_NAME%"

"%JAVA_HOME%\bin\jpackage.exe" ^
    --type %PKG_TYPE% ^
    --name "%APP_NAME%" ^
    --app-version %APP_VERSION% ^
    --vendor "LeoJasper" ^
    --input "%STAGING%" ^
    --main-jar "%MAIN_JAR%" ^
    --main-class "%MAIN_CLASS%" ^
    --java-options "-Xms256m -Xmx1g" ^
    --dest "%DIST_DIR%"
if errorlevel 1 exit /b %ERRORLEVEL%

rmdir /s /q "%STAGING%"

echo.
echo ============================================================
if /I "%PKG_TYPE%"=="app-image" (
    echo  App image ready at: %DIST_DIR%\%APP_NAME%\
    echo  Run %DIST_DIR%\%APP_NAME%\%APP_NAME%.exe
) else (
    echo  Installer ready in %DIST_DIR%\
)
echo ============================================================
endlocal
