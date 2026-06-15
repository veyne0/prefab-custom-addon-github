# 一键把 Prefab Custom Addon 推送到 GitHub
# 使用方法：在 PowerShell 里执行   .\push_to_github.ps1

$ErrorActionPreference = "Stop"
$dst = $PSScriptRoot

Set-Location $dst

# 检查 git
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Host "X  找不到 git，请先安装 Git for Windows" -ForegroundColor Red
    exit 1
}

# 检查是否已初始化
if (-not (Test-Path ".git")) {
    Write-Host ">> 初始化 git 仓库..." -ForegroundColor Cyan
    git init
    git branch -M main
}

# 询问 GitHub 仓库 URL（默认就是 veyne0/prefab-custom-addon）
$defaultUrl = "https://github.com/veyne0/prefab-custom-addon.git"
$repoUrl = Read-Host "请输入 GitHub 仓库 URL (回车用默认: $defaultUrl)"
if ([string]::IsNullOrWhiteSpace($repoUrl)) {
    $repoUrl = $defaultUrl
}

# 配置远程
$existingRemote = git remote get-url origin 2>$null
if ($existingRemote) {
    if ($existingRemote -ne $repoUrl) {
        Write-Host ">> 更新远程地址..." -ForegroundColor Cyan
        git remote set-url origin $repoUrl
    }
} else {
    Write-Host ">> 添加远程仓库..." -ForegroundColor Cyan
    git remote add origin $repoUrl
}

# 配置 git 用户（如果没设过）
$userName = git config user.name
$userEmail = git config user.email
if (-not $userName) {
    $name = Read-Host "请输入 git 用户名 (回车用 veyne)"
    if ([string]::IsNullOrWhiteSpace($name)) { $name = "veyne" }
    git config user.name $name
}
if (-not $userEmail) {
    $email = Read-Host "请输入 git 邮箱"
    git config user.email $email
}

# 暂存所有文件
Write-Host ">> 添加文件到暂存区..." -ForegroundColor Cyan
git add .

# 显示将要提交的文件
Write-Host ""
Write-Host "=== 待提交文件 ===" -ForegroundColor Yellow
git status --short

# 询问 commit message
$defaultMsg = "Initial release: Prefab Custom Addon v1.0.0"
$msg = Read-Host "`n请输入 commit 信息 (回车用默认: $defaultMsg)"
if ([string]::IsNullOrWhiteSpace($msg)) { $msg = $defaultMsg }

# 提交
Write-Host ">> 提交..." -ForegroundColor Cyan
git commit -m $msg

# 推送
Write-Host ""
Write-Host ">> 推送到 GitHub..." -ForegroundColor Cyan
Write-Host "   (首次推送会要求登录 GitHub: Personal Access Token 或浏览器)" -ForegroundColor Gray
git push -u origin main

# 完成
if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "=== 上传完成 ===" -ForegroundColor Green
    Write-Host "GitHub 仓库: $repoUrl" -ForegroundColor Green
    Write-Host ""
    Write-Host "接下来可以:" -ForegroundColor Yellow
    Write-Host "1. 到 GitHub 添加项目描述、Topics (minecraft, neoforge, prefab)"
    Write-Host "2. 设置 About 栏: Description / Website / Topics"
    Write-Host "3. 创建第一个 Release (v1.0.0) 并上传 build/libs/prefab-custom-addon-1.0.0.jar"
    Write-Host "4. 到 modrinth.com 创建同名项目, 上传 jar"
}
else {
    Write-Host ""
    Write-Host "X  推送失败，请检查上面的错误信息" -ForegroundColor Red
    Write-Host "   常见问题: 远程仓库已存在文件 / 凭证错误 / 网络问题" -ForegroundColor Gray
}
