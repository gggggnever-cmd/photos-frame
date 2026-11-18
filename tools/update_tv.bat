@echo off
rem Installs/updates Photos Frame on the TV over Wi-Fi. Windows.
rem Usage: update_tv.bat [TV-IP]   (put this file, adb.exe and PhotosFrame.apk in one folder)
cd /d "%~dp0"
set IP=%1
if "%IP%"=="" if exist tv_ip.txt set /p IP=<tv_ip.txt
if "%IP%"=="" (echo Give the TV's IP the first time: update_tv.bat 192.168.1.42 & exit /b 1)
if not exist PhotosFrame.apk (echo Can't find PhotosFrame.apk next to this file. & exit /b 1)
echo %IP%>tv_ip.txt
adb connect %IP%:5555 | find "connected" >nul || (echo Couldn't reach the TV. Check it is on, on the same Wi-Fi, and debugging is still on. & exit /b 1)
adb -s %IP%:5555 install -r PhotosFrame.apk || exit /b 1
adb -s %IP%:5555 shell settings put secure screensaver_components com.gary.photosframe/.FrameDreamService
adb -s %IP%:5555 shell settings put secure screensaver_enabled 1
adb -s %IP%:5555 shell am start -n com.gary.photosframe/.MainActivity >nul
echo Done: Photos Frame updated and opened on the TV.
