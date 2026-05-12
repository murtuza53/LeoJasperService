@echo off
setlocal enabledelayedexpansion

REM ----------------------------------------------------------------------
REM Build a self-contained portable zip that runs without a JDK on target.
REM Output: dist\LeoJasperService-<version>.zip
REM ----------------------------------------------------------------------

set "JAVA_HOME=C:\Program Files\Java\jdk-26.0.1"
set "MAVEN_HOME=C:\apache-maven-3.9.12"
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"
set "PROJECT_DIR=%~dp0"
set "DIST_DIR=%PROJECT_DIR%dist"
set "VERSION=1.0.0"
set "BUNDLE_NAME=LeoJasperService-%VERSION%"
set "BUNDLE_DIR=%DIST_DIR%\%BUNDLE_NAME%"

if not exist "%JAVA_HOME%\bin\jlink.exe" (
    echo [ERROR] jlink not found at "%JAVA_HOME%\bin\jlink.exe".
    echo         Edit JAVA_HOME at the top of this script.
    exit /b 1
)

REM 1. Compile and package the fat jar.
echo === [1/5] mvn package ===
call "%PROJECT_DIR%build.bat" -DskipTests package
if errorlevel 1 exit /b %ERRORLEVEL%

REM 2. Wipe the previous bundle dir.
echo.
echo === [2/5] cleaning %BUNDLE_DIR% ===
if exist "%BUNDLE_DIR%" rmdir /s /q "%BUNDLE_DIR%"
mkdir "%BUNDLE_DIR%\app"
mkdir "%BUNDLE_DIR%\templates"

REM 3. Build a stripped JRE with jlink — modules picked to cover Spring Boot,
REM    JasperReports, PDFBox, and JDBC. If you hit "Module ... not found" at
REM    runtime, fall back to: jlink --add-modules ALL-MODULE-PATH
echo.
echo === [3/5] jlink — building stripped JRE ===
"%JAVA_HOME%\bin\jlink.exe" ^
    --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.management.rmi,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.compiler,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.dynalink,jdk.httpserver,jdk.jfr,jdk.management,jdk.management.agent,jdk.naming.dns,jdk.naming.rmi,jdk.unsupported,jdk.zipfs ^
    --output "%BUNDLE_DIR%\jre" ^
    --no-header-files ^
    --no-man-pages ^
    --strip-debug ^
    --compress=zip-9
if errorlevel 1 (
    echo [ERROR] jlink failed.
    exit /b %ERRORLEVEL%
)

REM 4. Copy artifacts and bundle scripts.
echo.
echo === [4/5] staging artifacts ===
copy /Y "%PROJECT_DIR%leo-jasper-rest\target\leo-jasper-rest.jar"     "%BUNDLE_DIR%\app\app.jar"  >nul
copy /Y "%PROJECT_DIR%leo-jasper-cli\target\leo-jasper-cli-shaded.jar" "%BUNDLE_DIR%\app\cli.jar"  >nul
if exist "%PROJECT_DIR%templates" copy /Y "%PROJECT_DIR%templates\*.jrxml" "%BUNDLE_DIR%\templates\" >nul 2>&1
copy /Y "%PROJECT_DIR%dist-templates\run.bat"     "%BUNDLE_DIR%\run.bat"     >nul
copy /Y "%PROJECT_DIR%dist-templates\README.txt"  "%BUNDLE_DIR%\README.txt"  >nul

REM 5. Zip it up using PowerShell's built-in Compress-Archive.
echo.
echo === [5/5] zipping ===
if exist "%DIST_DIR%\%BUNDLE_NAME%.zip" del /q "%DIST_DIR%\%BUNDLE_NAME%.zip"
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "Compress-Archive -Path '%BUNDLE_DIR%\*' -DestinationPath '%DIST_DIR%\%BUNDLE_NAME%.zip' -Force"
if errorlevel 1 exit /b %ERRORLEVEL%

for %%I in ("%DIST_DIR%\%BUNDLE_NAME%.zip") do set "ZIPSIZE=%%~zI"
set /a "ZIPMB=%ZIPSIZE% / 1024 / 1024"

echo.
echo ============================================================
echo  Bundle ready
echo ============================================================
echo  %DIST_DIR%\%BUNDLE_NAME%.zip   (%ZIPMB% MB)
echo.
echo  Recipient extracts the zip and runs run.bat — that's it.
echo ============================================================
endlocal
