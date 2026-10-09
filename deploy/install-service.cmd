@echo off
REM  ---------------------------------------------------------------
REM  本文件必须保存为 ANSI/GBK + CRLF 换行，且不要加 chcp 65001。
REM  原因：cmd.exe 在 65001 代码页下读批处理文件存在缓冲区错位 bug，
REM        会把中文行的后半截当成命令执行（报“不是内部或外部命令”）。
REM  用 VS Code / Notepad++ 编辑时请确认右下角编码是 GB2312/GBK。
REM  ---------------------------------------------------------------
setlocal
title OceanEngine 服务安装

REM ============================================================
REM  安装并启动 OceanEngine 随心推追投服务
REM  注意： 必须以【管理员身份】运行本脚本，否则服务安装会失败
REM
REM  运行前需准备好：
REM    1) D:\oceanengine\oceanengine-service.exe   （WinSW-x64.exe 重命名而来）
REM    2) D:\oceanengine\oceanengine-service.xml   （来自 deploy\oceanengine-service.xml）
REM    3) D:\oceanengine\oceanengine-1.0-SNAPSHOT.jar
REM    4) D:\oceanengine\config\application.yml     （已按第 6 节改好绝对路径）
REM    5) 系统环境变量 QIANCHUAN_SERVICE_BEARER_TOKEN
REM    6) D:\oceanengine\data\currentreport\每日追投明细表模板.xls  （日报模板，缺失只影响日报）
REM ============================================================

set "SERVICE_HOME=D:\oceanengine"
set "WINSW_EXE=%SERVICE_HOME%\oceanengine-service.exe"
set "WINSW_XML=%SERVICE_HOME%\oceanengine-service.xml"
set "JAR=%SERVICE_HOME%\oceanengine-1.0-SNAPSHOT.jar"
set "APP_YML=%SERVICE_HOME%\config\application.yml"
set "REPORT_TEMPLATE=%SERVICE_HOME%\data\currentreport\每日追投明细表模板.xls"

echo [install] 检查前置条件...

if not exist "%WINSW_EXE%" (
    echo   错误：未找到 %WINSW_EXE%
    echo   请到 https://github.com/winsw/winsw/releases 下载 WinSW-x64.exe
    echo   重命名为 oceanengine-service.exe 后放到 %SERVICE_HOME%
    exit /b 1
)

if not exist "%WINSW_XML%" (
    echo   错误：未找到 %WINSW_XML%
    exit /b 1
)

if not exist "%JAR%" (
    echo   错误：未找到 %JAR%
    exit /b 1
)

if not exist "%APP_YML%" (
    echo   错误：未找到 %APP_YML%
    echo   外部配置缺失，程序会退回使用 jar 内置的 application.yml
    exit /b 1
)

if not defined QIANCHUAN_SERVICE_BEARER_TOKEN (
    echo   错误：系统环境变量 QIANCHUAN_SERVICE_BEARER_TOKEN 未配置
    echo   必须配置为“系统变量”而不是“用户变量”，否则服务账户读不到
    echo   配置完成后需要重开命令行窗口再运行本脚本
    exit /b 1
)

if not exist "%SERVICE_HOME%\logs" mkdir "%SERVICE_HOME%\logs"
if not exist "%SERVICE_HOME%\data" mkdir "%SERVICE_HOME%\data"

REM 日报模板缺失不阻止启动（只影响日报，不连累追投），所以这里只提示不退出
if not exist "%REPORT_TEMPLATE%" (
    echo.
    echo   提示：未找到日报模板 %REPORT_TEMPLATE%
    echo   服务仍会正常追投，但每日追投明细表会生成失败。
    echo   若模板放在别的位置，请同步修改 config\application.yml 里的
    echo     suixintui.report.template-path
    echo.
)

REM 服务已存在时给出明确指引，避免 WinSW 报 already exists 让人以为脚本坏了。
REM WinSW 的 status 退出码：0=运行中, 1=已停止, 3=未安装。
"%WINSW_EXE%" status >nul 2>&1
if not errorlevel 3 (
    echo.
    echo   注意：服务 oceanengine 已经安装过了，本次不再重复安装。
    echo   如果只是想让它读取新的 config\application.yml：请运行 restart-service.cmd
    echo   如果想彻底重装：先运行 uninstall-service.cmd，再运行本脚本
    echo.
    exit /b 1
)

echo [install] 安装服务...
"%WINSW_EXE%" install
if errorlevel 1 (
    echo   错误：服务安装失败，请确认是以管理员身份运行
    exit /b 1
)

echo [install] 启动服务...
"%WINSW_EXE%" start
if errorlevel 1 (
    echo   错误：服务启动失败
    echo   查看 %SERVICE_HOME%\logs\oceanengine-service.err.log
    exit /b 1
)

echo.
echo [完成] 服务已安装并启动
echo   状态查询：sc query oceanengine
echo   健康检查：curl http://127.0.0.1:8080/actuator/health
echo   框架日志（启动异常看这个）：%SERVICE_HOME%\logs\application.log
echo   业务日志（追投/日报结果看这个）：%SERVICE_HOME%\logs\oceanengine-service.out.log
endlocal
