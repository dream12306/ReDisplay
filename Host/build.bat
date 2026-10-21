@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo ============================================================
echo   Building ReDisplay Windows Host (ReDisplayHost.exe)
echo ============================================================

set "VCVARS=C:\Program Files\Microsoft Visual Studio\18\Insiders\VC\Auxiliary\Build\vcvarsall.bat"
if not exist "%VCVARS%" set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvarsall.bat"
if not exist "%VCVARS%" set "VCVARS=C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvarsall.bat"
if not exist "%VCVARS%" (
    echo [ERROR] A supported Visual Studio C++ toolchain was not found.
    exit /b 1
)

call "%VCVARS%" x64 >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Failed to initialize MSVC environment!
    exit /b 1
)

set HOST_DIR=%~dp0
set SRC_DIR=%HOST_DIR%src
set INC_DIR=%HOST_DIR%include

echo Running bounded network queue regression tests...
cl.exe /nologo /EHsc /std:c++20 /O2 /MD ^
    /I "%SRC_DIR%" ^
    "%HOST_DIR%tests\bounded_frame_queue_test.cpp" ^
    /Fe:"%HOST_DIR%bounded_frame_queue_test.exe"
if %errorlevel% neq 0 exit /b 1
"%HOST_DIR%bounded_frame_queue_test.exe"
if %errorlevel% neq 0 exit /b 1
del "%HOST_DIR%bounded_frame_queue_test.exe" >nul 2>&1
del "%HOST_DIR%bounded_frame_queue_test.obj" >nul 2>&1

echo Running stream latency policy regression tests...
cl.exe /nologo /EHsc /std:c++20 /O2 /MD ^
    /I "%SRC_DIR%" ^
    "%HOST_DIR%tests\stream_latency_policy_test.cpp" ^
    /Fe:"%HOST_DIR%stream_latency_policy_test.exe"
if %errorlevel% neq 0 exit /b 1
"%HOST_DIR%stream_latency_policy_test.exe"
if %errorlevel% neq 0 exit /b 1
del "%HOST_DIR%stream_latency_policy_test.exe" >nul 2>&1
del "%HOST_DIR%stream_latency_policy_test.obj" >nul 2>&1

echo Running ADB path resolution regression test...
cl.exe /nologo /EHsc /std:c++20 /O2 /MD ^
    /I "%SRC_DIR%" ^
    "%HOST_DIR%tests\adb_path_test.cpp" ^
    "%SRC_DIR%\adb.cpp" ^
    /Fe:"%HOST_DIR%adb_path_test.exe"
if %errorlevel% neq 0 exit /b 1
"%HOST_DIR%adb_path_test.exe"
if %errorlevel% neq 0 exit /b 1
del "%HOST_DIR%adb_path_test.exe" >nul 2>&1
del "%HOST_DIR%adb_path_test.obj" >nul 2>&1
del "%HOST_DIR%adb.obj" >nul 2>&1

echo Running pen input mapping regression tests...
cl.exe /nologo /EHsc /std:c++20 /O2 /MD ^
    /I "%SRC_DIR%" ^
    "%HOST_DIR%tests\pen_input_math_test.cpp" ^
    /Fe:"%HOST_DIR%pen_input_math_test.exe"
if %errorlevel% neq 0 exit /b 1
"%HOST_DIR%pen_input_math_test.exe"
if %errorlevel% neq 0 exit /b 1
del "%HOST_DIR%pen_input_math_test.exe" >nul 2>&1
del "%HOST_DIR%pen_input_math_test.obj" >nul 2>&1

echo Running pen macro recorder regression tests...
cl.exe /nologo /EHsc /std:c++20 /O2 /MD ^
    /I "%SRC_DIR%" ^
    "%HOST_DIR%tests\pen_macro_test.cpp" ^
    /Fe:"%HOST_DIR%pen_macro_test.exe" ws2_32.lib
if %errorlevel% neq 0 exit /b 1
"%HOST_DIR%pen_macro_test.exe"
if %errorlevel% neq 0 exit /b 1
del "%HOST_DIR%pen_macro_test.exe" >nul 2>&1
del "%HOST_DIR%pen_macro_test.obj" >nul 2>&1

echo Running wheel config validation tests...
cl.exe /nologo /EHsc /std:c++20 /O2 /MD ^
    /I "%SRC_DIR%" ^
    "%HOST_DIR%tests\wheel_config_test.cpp" ^
    "%SRC_DIR%\wheel_config.cpp" ^
    /Fe:"%HOST_DIR%wheel_config_test.exe" ws2_32.lib
if %errorlevel% neq 0 exit /b 1
"%HOST_DIR%wheel_config_test.exe"
if %errorlevel% neq 0 exit /b 1
del "%HOST_DIR%wheel_config_test.exe" >nul 2>&1
del "%HOST_DIR%wheel_config_test.obj" >nul 2>&1
del "%HOST_DIR%wheel_config.obj" >nul 2>&1

echo Compiling C++20 sources with MSVC optimizations (/O2)...
cl.exe /nologo /EHsc /std:c++20 /O2 /MD ^
    /D_SILENCE_EXPERIMENTAL_COROUTINE_DEPRECATION_WARNINGS ^
    /I "%INC_DIR%" /I "%SRC_DIR%" ^
    "%SRC_DIR%\main.cpp" ^
    "%SRC_DIR%\adb.cpp" ^
    "%SRC_DIR%\display_manager.cpp" ^
    "%SRC_DIR%\pen_input.cpp" ^
    "%SRC_DIR%\wheel_config.cpp" ^
    "%SRC_DIR%\encoder.cpp" ^
    "%SRC_DIR%\capture.cpp" ^
    /link /OUT:"%HOST_DIR%ReDisplayHost.exe" ^
    windowsapp.lib d3d11.lib dxgi.lib ws2_32.lib user32.lib

if %errorlevel% equ 0 (
    copy /y "%HOST_DIR%ReDisplayHost.exe" "%HOST_DIR%ReDisplay.exe" >nul 2>&1
    echo ============================================================
    echo   SUCCESS: ReDisplayHost.exe built successfully!
    echo   Output: %HOST_DIR%ReDisplayHost.exe
    echo ============================================================
    del "%HOST_DIR%*.obj" >nul 2>&1
) else (
    echo [ERROR] Compilation failed!
    exit /b 1
)
