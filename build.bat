@echo off
setlocal

set "JAVA_HOME=C:\Program Files\Java\jdk-26.0.1"
set "MAVEN_HOME=C:\apache-maven-3.9.15"
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"
set "PROJECT_DIR=%~dp0"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK not found at "%JAVA_HOME%".
    exit /b 1
)
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo [ERROR] Maven not found at "%MAVEN_HOME%".
    exit /b 1
)

REM Pass-through args, e.g. build.bat -DskipTests, build.bat clean package, etc.
set "MVN_ARGS=%*"
if "%MVN_ARGS%"=="" set "MVN_ARGS=-DskipTests package"

pushd "%PROJECT_DIR%"
echo Building LeoJasperService with: mvn %MVN_ARGS%
echo.
call "%MAVEN_HOME%\bin\mvn.cmd" -B -ntp %MVN_ARGS%
set "RC=%ERRORLEVEL%"
popd

endlocal & exit /b %RC%
