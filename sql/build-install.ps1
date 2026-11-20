# Assembly script for sql/00_install.sql.
#
# WHY THIS FILE IS ASCII-ONLY:
#   Windows PowerShell 5.1 reads a .ps1 file as ANSI unless the file
#   starts with a UTF-8 BOM. Any Cyrillic literal inside the script
#   would therefore be corrupted. To make the build immune to the code
#   page it runs under, this script contains no non-ASCII characters:
#   all Russian text lives in separate UTF-8 data files
#   (install-head.sql, install-tail.sql) which are read with an
#   explicit UTF-8 encoding.
#
# USAGE:
#   powershell -ExecutionPolicy Bypass -File sql\build-install.ps1
#
# Run it again after changing 00_reset.sql, 01_schema.sql or 02_objects.sql.

$ErrorActionPreference = 'Stop'

$sqlDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$utf8 = New-Object Text.UTF8Encoding($false)

function Read-Utf8([string]$path) {
    return [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)
}

$sections = @(
    @{ file = 'install-head.sql'; banner = '' },
    @{ file = '00_reset.sql';      banner = "1. SCHEMA RESET" },
    @{ file = '01_schema.sql';     banner = "2. DOMAINS, TABLES, INDEXES" },
    @{ file = '02_objects.sql';    banner = "3. TRIGGERS, VIEWS, FUNCTIONS" }
)

$out = New-Object Text.StringBuilder

foreach ($section in $sections) {
    $path = Join-Path $sqlDir $section.file
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing source file: $path"
    }
    [void]$out.AppendLine((Read-Utf8 $path))
    [void]$out.AppendLine('')
    if ($section.banner -ne '') {
        [void]$out.AppendLine("-- ---------------------------------------------------------------------")
        [void]$out.AppendLine("-- $($section.banner)")
        [void]$out.AppendLine("-- ---------------------------------------------------------------------")
        [void]$out.AppendLine('')
    }
}

$tail = Join-Path $sqlDir 'install-tail.sql'
if (-not (Test-Path -LiteralPath $tail)) {
    throw "Missing source file: $tail"
}
[void]$out.AppendLine((Read-Utf8 $tail))

$target = Join-Path $sqlDir '00_install.sql'
[IO.File]::WriteAllText($target, $out.ToString(), $utf8)

$size = (Get-Item -LiteralPath $target).Length
Write-Output "Assembled $target ($size bytes)"
