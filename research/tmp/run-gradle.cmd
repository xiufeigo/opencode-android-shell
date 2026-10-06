@echo off
REM usage: run-gradle.cmd <logfile> <gradle args...>
setlocal
set JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d D:\XiaoZhuTouTou\opencode-android-shell
set LOG=%1
shift
set ARGS=
:loop
if "%1"=="" goto done
set ARGS=%ARGS% %1
shift
goto loop
:done
if exist "%LOG%" del "%LOG%"
call gradlew.bat%ARGS% --console=plain --no-daemon > "%LOG%" 2>&1
set RC=%ERRORLEVEL%
echo EXITCODE=%RC% >> "%LOG%"
exit /b %RC%
