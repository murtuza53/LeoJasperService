@echo off
setlocal
set "BUNDLE_DIR=%~dp0"
set "JAVA_BIN=%BUNDLE_DIR%jre\bin\java.exe"
set "APP_JAR=%BUNDLE_DIR%app\app.jar"

if not exist "%JAVA_BIN%" (
    echo [ERROR] Bundled JRE not found at "%JAVA_BIN%".
    echo         The bundle may be incomplete — re-extract the zip.
    exit /b 1
)
if not exist "%APP_JAR%" (
    echo [ERROR] App jar not found at "%APP_JAR%".
    exit /b 1
)

if "%JAVA_OPTS%"=="" set "JAVA_OPTS=-Xms256m -Xmx1g"

REM Templates resolve relative to the working dir (see leojasper.templates.path).
cd /d "%BUNDLE_DIR%"

echo Starting LeoJasperService on http://localhost:8080
echo   Bundle: %BUNDLE_DIR%
echo   Opts:   %JAVA_OPTS%
echo   Press Ctrl-C to stop.
echo.

"%JAVA_BIN%" %JAVA_OPTS% -jar "%APP_JAR%" %*
endlocal
