@echo off
chcp 936 >nul
setlocal enabledelayedexpansion
title 集成手机端 APK 打包安装脚本

echo ============================================================
echo       集成手机端(手机远程) APK 打包安装脚本
echo ============================================================
echo.

rem ================= 1. 设置Java环境 =================
set "JAVA_HOME=E:\Andorid Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [错误] 未找到JDK: %JAVA_HOME%
    echo 请修改脚本中的 JAVA_HOME 路径
    pause
    exit /b 1
)
echo JDK路径: %JAVA_HOME%
echo.

rem ================= 2. 构建APK =================
echo [1/4] Gradle 构建集成手机端 APK，约1~3分钟，请稍候...
cd /d "%~dp0android_controller"
call .\gradlew.bat assembleDebug --console=plain > "%TEMP%\sf_build.log" 2>&1
set "BUILD_CODE=%ERRORLEVEL%"
type "%TEMP%\sf_build.log" | findstr /v /c:"R8: Expected stack map table" /c:"In later version of R8" | findstr /i "error warning BUILD"
del "%TEMP%\sf_build.log" >nul 2>&1
if not "%BUILD_CODE%"=="0" (
    echo [错误] 构建失败，请查看上方错误信息。
    pause
    exit /b 1
)
echo [1/4] 构建成功。
echo.

rem ================= 3. 定位APK并复制到桌面 =================
echo [2/4] 定位APK文件并复制到桌面...
set "APK_DIR=app\build\outputs\apk\debug"
set "APK_FILE="
for /f "delims=" %%f in ('dir /b "%APK_DIR%\*.apk" 2^>nul') do set "APK_FILE=%%f"
if not defined APK_FILE (
    echo [错误] 未找到生成的APK文件
    pause
    exit /b 1
)
set "DESKTOP=%USERPROFILE%\Desktop"
if not exist "%DESKTOP%" set "DESKTOP=%USERPROFILE%\OneDrive\Desktop"
if not exist "%DESKTOP%" set "DESKTOP=%USERPROFILE%\桌面"
set "OUT_APK=%DESKTOP%\手机远程_集成版.apk"
copy /y "%APK_DIR%\%APK_FILE%" "%OUT_APK%" >nul
echo [2/4] APK已保存到: %OUT_APK%
echo.

rem ================= 4. 查找adb并安装 =================
echo [3/4] 检测手机设备...
set "ADB="
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if not defined ADB if exist "C:\Users\%USERNAME%\AppData\Local\Android\Sdk\platform-tools\adb.exe" set "ADB=C:\Users\%USERNAME%\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if not defined ADB (
    where adb.exe >nul 2>&1
    if not errorlevel 1 set "ADB=adb.exe"
)
if not defined ADB (
    echo [错误] 未找到 adb 工具，请安装 Android SDK Platform-Tools
    pause
    exit /b 1
)
"%ADB%" devices
"%ADB%" install -r "%OUT_APK%"
if errorlevel 1 (
    echo [错误] 安装失败
    pause
    exit /b 1
)
echo [3/4] 安装成功。
echo.

rem ================= 5. 启动验证 =================
echo [4/4] 启动应用验证...
"%ADB%" shell am start -n com.example.remotecontroller/com.example.remoteconsole.MainActivity >nul 2>&1
echo [4/4] 应用已启动。
echo.
echo ============================================================
echo   全部完成！集成手机端 APK 已保存到桌面并安装到手机
echo ============================================================
pause
exit /b 0
