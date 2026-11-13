@echo off
title MS PLAYER - Windows 11 Desktop Edition
setlocal
set "VLC_DIR=C:\Program Files\VideoLAN\VLC"
if exist "%VLC_DIR%" set "PATH=%VLC_DIR%;%PATH%"
python "%~dp0msplayer_desktop.py" %*
