@echo off
setlocal
set DIR=%~dp0
if not defined JAVA_HOME goto findJavaFromPath
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
if exist "%JAVA_EXE%" goto runWrapper

:findJavaFromPath
set JAVA_EXE=java.exe

:runWrapper
if not exist "%DIR%gradle\wrapper\gradle-wrapper.jar" (
    echo Gradle Wrapper jar bulunamadi. Gradle kuruluysa "gradle wrapper --gradle-version 8.10.2" calistirin.
    exit /b 1
)
"%JAVA_EXE%" -classpath "%DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal
