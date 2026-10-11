# ============================================================
# 本地/内网部署一键启动脚本(Windows PowerShell)
# 用法(在本目录执行):
#   powershell -ExecutionPolicy Bypass -File .\deploy.ps1
# 前提:本机已装 MySQL(root,库 campus_social_v2)+ Redis(6379),密码见 .env
#       后端已 mvn 打包(target/campus-social-1.0.0.jar)
# ============================================================

$ErrorActionPreference = "Stop"

# ---------- 1. 检查后端 jar ----------
$jar = "..\backend\target\campus-social-1.0.0.jar"
if (-not (Test-Path $jar)) {
    Write-Host "未找到后端 jar: $jar"
    Write-Host "请先在 backend/ 执行: mvn -DskipTests package"
    exit 1
}

# ---------- 2. 检查前端构建产物 ----------
$dist = "frontend\dist\index.html"
if (-not (Test-Path $dist)) {
    Write-Host "未找到前端构建产物,正在执行 vite build ..."
    Push-Location frontend
    npm run build
    Pop-Location
}

# ---------- 3. 启动后端(带本机明文配置 local profile)----------
# application-local.yml 里有本机 MySQL/Redis 密码,用 local profile 激活
Write-Host "启动后端 Spring Boot ..."
Start-Process java -ArgumentList "`"$jar`"", "--spring.profiles.active=local" -PassThru |
    ForEach-Object {
        Write-Host "后端 PID: $($_.Id)"
    }

# ---------- 4. 启动前端静态服务(用简单 http 服务 + nginx 可选)----------
Write-Host "前端静态文件位于 frontend/dist,可用任意静态服务:"
Write-Host "  - python -m http.server 8080 -d frontend/dist   (仅演示)"
Write-Host "  - 或部署到 Nginx(生产推荐,反代 /api 与 /ws 到 8080)"

Write-Host "完成。后端访问 http://localhost:8080/swagger-ui.html"
Write-Host "本地开发模式请改用: 后端 mvn spring-boot:run + 前端 npm run dev"
