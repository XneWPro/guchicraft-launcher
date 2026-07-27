Set shell = CreateObject("WScript.Shell")
Set fileSystem = CreateObject("Scripting.FileSystemObject")

launcherRoot = fileSystem.GetParentFolderName(WScript.ScriptFullName)
shell.CurrentDirectory = launcherRoot

command = Chr(34) & launcherRoot & "\runtime\bin\javaw.exe" & Chr(34) & _
  " --module-path " & Chr(34) & launcherRoot & "\javafx" & Chr(34) & _
  " --add-modules javafx.controls" & _
  " -Dfile.encoding=UTF-8" & _
  " -Dguchicraft.launcher.version=1.0.9" & _
  " -Dguchicraft.launcher.root=" & Chr(34) & launcherRoot & Chr(34) & _
  " -Dguchicraft.updater.jar=" & Chr(34) & launcherRoot & "\updater\guchicraft-updater.jar" & Chr(34) & _
  " -cp " & Chr(34) & launcherRoot & "\app\*" & Chr(34) & _
  " ru.ezcraft.launcher.LauncherBootstrap"

shell.Run command, 0, False
