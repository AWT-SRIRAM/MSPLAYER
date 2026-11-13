Set WshShell = CreateObject("WScript.Shell")
scriptDir = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
pywExe = "C:\Users\DELL\AppData\Local\Programs\Python\Python312\pythonw.exe"
playerScript = scriptDir & "\msplayer_desktop.py"
WshShell.CurrentDirectory = scriptDir
WshShell.Run """" & pywExe & """ """ & playerScript & """", 0, False

