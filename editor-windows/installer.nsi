Unicode true
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"
!include "WinVer.nsh"

!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\TXTDesignNSIS"

Name "TXTDesign ${APP_VERSION}"
OutFile "${OUTPUT_EXE}"
InstallDir "$LOCALAPPDATA\Programs\TXTDesign"
InstallDirRegKey HKCU "${UNINSTALL_KEY}" "InstallLocation"
RequestExecutionLevel user
SetCompressor /SOLID lzma
ShowInstDetails show
ShowUninstDetails show

VIProductVersion "${APP_VERSION}.0"
VIAddVersionKey /LANG=2052 "ProductName" "TXTDesign"
VIAddVersionKey /LANG=2052 "ProductVersion" "${APP_VERSION}"
VIAddVersionKey /LANG=2052 "FileVersion" "${APP_VERSION}"
VIAddVersionKey /LANG=2052 "FileDescription" "TXTDesign 简文本安装程序"

!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\TXTDesign.exe"
!define MUI_FINISHPAGE_RUN_TEXT "启动 TXTDesign"
!define MUI_FINISHPAGE_RUN_NOTCHECKED
!define MUI_FINISHPAGE_TEXT "安装完成。编辑器的设置和草稿保存在当前用户的数据目录，更新和卸载都会保留它们。"
!insertmacro MUI_PAGE_WELCOME
!define MUI_PAGE_HEADER_TEXT "第三方许可"
!define MUI_PAGE_HEADER_SUBTEXT "查看随 TXTDesign 提供的组件许可。"
!insertmacro MUI_PAGE_LICENSE "${LICENSE_FILE}"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "SimpChinese"

Function .onInit
    SetShellVarContext current
    SetRegView 64
    ${IfNot} ${RunningX64}
        MessageBox MB_ICONSTOP "TXTDesign 需要 64 位 Windows。" /SD IDOK
        Abort
    ${EndIf}
    ${IfNot} ${AtLeastWin10}
        MessageBox MB_ICONSTOP "TXTDesign 需要 Windows 10 或更新版本。" /SD IDOK
        Abort
    ${EndIf}
FunctionEnd

Section "TXTDesign"
    SetOutPath "$INSTDIR"
    File /r "${APP_DIR}\*.*"
    WriteUninstaller "$INSTDIR\Uninstall.exe"
    CreateShortcut "$SMPROGRAMS\TXTDesign 简文本.lnk" "$INSTDIR\TXTDesign.exe"
    CreateShortcut "$DESKTOP\TXTDesign 简文本.lnk" "$INSTDIR\TXTDesign.exe"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayName" "TXTDesign 简文本"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayVersion" "${APP_VERSION}"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "InstallLocation" "$INSTDIR"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayIcon" "$INSTDIR\TXTDesign.exe"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
    WriteRegStr HKCU "${UNINSTALL_KEY}" "QuietUninstallString" '$\"$INSTDIR\Uninstall.exe$\" /S'
    WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoModify" 1
    WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoRepair" 1
SectionEnd

Function un.onInit
    SetShellVarContext current
    SetRegView 64
FunctionEnd

Section "Uninstall"
    Delete "$SMPROGRAMS\TXTDesign 简文本.lnk"
    Delete "$DESKTOP\TXTDesign 简文本.lnk"
    DeleteRegKey HKCU "${UNINSTALL_KEY}"
    !include "${UNINSTALL_FILES}"
    Delete "$INSTDIR\Uninstall.exe"
    RMDir "$INSTDIR"
SectionEnd
