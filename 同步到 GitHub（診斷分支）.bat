@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

set REPO=https://github.com/cm745633/music-ducking.git
set BRANCH=raw-wav

echo ============================================
echo  同步這個資料夾到 GitHub 分支: %BRANCH%
echo ============================================
echo.

where git >nul 2>nul
if errorlevel 1 (
  echo [x] 找不到 git。
  echo.
  echo     請先安裝 Git for Windows：https://git-scm.com/download/win
  echo     安裝時全部按預設值即可，裝完把這個視窗關掉重開一次。
  echo.
  pause
  exit /b 1
)

if not exist ".git" (
  echo [1/5] 第一次執行，正在初始化...
  git init -b %BRANCH% >nul
  git remote add origin %REPO%
  git config core.autocrlf false
  git config user.name >nul 2>nul
  if errorlevel 1 git config user.name "cm745633"
  git config user.email >nul 2>nul
  if errorlevel 1 git config user.email "cm745633@users.noreply.github.com"
  echo [2/5] 取得 GitHub 上的現況...
  git fetch origin main
  if errorlevel 1 goto :failed
  git reset --soft origin/main
) else (
  echo [1/5] 已初始化，略過
  echo [2/5] 取得 GitHub 上的現況...
  git fetch origin main
  if errorlevel 1 goto :failed
)

echo [3/5] 比對差異...
git add -A
git diff --cached --stat
git diff --cached --quiet
if not errorlevel 1 (
  echo.
  echo     沒有任何變更，不需要上傳。
  echo.
  pause
  exit /b 0
)

echo.
echo [4/5] 建立提交...
if "%~1"=="" (
  git commit -m "Update from local folder" >nul
) else (
  git commit -m "%~1" >nul
)
if errorlevel 1 goto :failed

echo [5/5] 上傳中（第一次會跳出瀏覽器要你登入 GitHub）...
git push origin %BRANCH%
if errorlevel 1 goto :failed

echo.
echo ============================================
echo  完成。到這裡看建置進度：
echo  https://github.com/cm745633/music-ducking/actions
echo ============================================
echo.
pause
exit /b 0

:failed
echo.
echo [x] 出錯了。把上面的紅字或英文訊息整段複製給我。
echo.
pause
exit /b 1
