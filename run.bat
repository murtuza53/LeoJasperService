@echo off
setlocal

REM --- Locate JDK and project ----------------------------------------------
set "JAVA_HOME=C:\Program Files\Java\jdk-26.0.1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "PROJECT_DIR=%~dp0"
set "APP_JAR=%PROJECT_DIR%leo-jasper-rest\target\leo-jasper-rest.jar"

REM --- Sanity checks --------------------------------------------------------
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK not found at "%JAVA_HOME%".
    echo         Edit run.bat and point JAVA_HOME at your JDK install.
    exit /b 1
)
if not exist "%APP_JAR%" (
    echo [ERROR] App jar not found: "%APP_JAR%"
    echo         Run "build.bat" first, or:
    echo             "C:\apache-maven-3.9.15\bin\mvn.cmd" -DskipTests package
    exit /b 1
)

REM --- Optional JVM tuning --------------------------------------------------
if "%JAVA_OPTS%"=="" set "JAVA_OPTS=-Xms256m -Xmx1g"

echo Starting LeoJasperService REST on http://localhost:8080
echo   JDK:   %JAVA_HOME%
echo   App:   %APP_JAR%
echo   Opts:  %JAVA_OPTS%
echo.

"%JAVA_HOME%\bin\java.exe" %JAVA_OPTS% -jar "%APP_JAR%" %*

endlocal
