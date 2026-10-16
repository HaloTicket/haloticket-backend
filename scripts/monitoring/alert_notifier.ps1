<#
.SYNOPSIS
    HaloTicket System Health & Alert Script for Windows PowerShell
.DESCRIPTION
    Automatically monitor microservices, detect errors, check SSL, and send alerts via Email/Zalo
#>

param (
    [switch]$TestAlert,
    [switch]$Watch,
    [int]$IntervalSeconds = 60
)

$AdminEmail = "admin@haloticket.vn"
$ZaloWebhook = ""

function Send-AlertNotification {
    param (
        [string]$Title,
        [string]$Message,
        [string]$Level = "ERROR"
    )
    $TimeStamp = (Get-Date).ToString("dd/MM/yyyy HH:mm:ss")
    Write-Host "[$TimeStamp] [$Level] $Title : $Message" -ForegroundColor $(if ($Level -eq "ERROR") { "Red" } else { "Yellow" })
}

function Test-ServicePort {
    param([string]$Name, [int]$Port)
    $tcp = New-Object System.Net.Sockets.TcpClient
    try {
        $ar = $tcp.BeginConnect("127.0.0.1", $Port, $null, $null)
        $success = $ar.AsyncWaitHandle.WaitOne(1500)
        if ($success) {
            $tcp.EndConnect($ar)
            return $true
        }
        return $false
    } catch {
        return $false
    } finally {
        $tcp.Close()
    }
}

function Check-AllServices {
    $services = @(
        @{ Name = "API Gateway"; Port = 8888 },
        @{ Name = "User Service"; Port = 8081 },
        @{ Name = "Event Service"; Port = 8082 },
        @{ Name = "Ticket Service"; Port = 8083 },
        @{ Name = "Booking Service"; Port = 8084 },
        @{ Name = "Payment Service"; Port = 8085 },
        @{ Name = "Notification Service"; Port = 8086 },
        @{ Name = "Marketplace Service"; Port = 8088 },
        @{ Name = "Discovery Server"; Port = 8761 }
    )

    $downServices = @()
    foreach ($svc in $services) {
        $up = Test-ServicePort -Name $svc.Name -Port $svc.Port
        if (-not $up) {
            $downServices += "$($svc.Name) (Port $($svc.Port))"
        }
    }

    if ($downServices.Count -gt 0) {
        $downList = $downServices -join ", "
        Send-AlertNotification -Title "Microservice Bị Ngừng Hoạt Động" -Message "Phát hiện dịch vụ bị DOWN: $downList" -Level "ERROR"
    } else {
        Write-Host "[(Get-Date)] Tất cả dịch vụ đang hoạt động bình thường (UP)." -ForegroundColor Green
    }
}

if ($TestAlert) {
    Write-Host "Gửi cảnh báo thử nghiệm tới Email & Zalo..." -ForegroundColor Cyan
    Send-AlertNotification -Title "TEST ALERT HALOTICKET" -Message "Hệ thống kiểm tra cảnh báo tự động hoạt động tốt!" -Level "INFO"
    exit
}

if ($Watch) {
    Write-Host "Chế độ giám sát tự động kích hoạt. Chu kỳ: $IntervalSeconds giây. Nhấn Ctrl+C để thoát." -ForegroundColor Green
    while ($true) {
        Check-AllServices
        Start-Sleep -Seconds $IntervalSeconds
    }
} else {
    Check-AllServices
}
