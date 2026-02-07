@echo off
set DIRNAME=%~dp0
set JAVA_EXE=java.exe
"%JAVA_EXE%" -classpath "%DIRNAME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
