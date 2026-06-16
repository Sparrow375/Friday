# Generate Friday Wake-Word Voice Dataset using Native .NET SpeechSynthesizer
param(
    [string]$OutputDir = "temp_dataset"
)

$ErrorActionPreference = "Stop"

Write-Host "Initializing .NET SpeechSynthesizer..."
Add-Type -AssemblyName System.Speech
$speak = New-Object System.Speech.Synthesis.SpeechSynthesizer

$posDir = Join-Path $OutputDir "positive"
$negDir = Join-Path $OutputDir "negative"

# Recreate folders
if (Test-Path $OutputDir) {
    Remove-Item -Recurse -Force $OutputDir
}
New-Item -ItemType Directory -Force -Path $posDir | Out-Null
New-Item -ItemType Directory -Force -Path $negDir | Out-Null

$voices = $speak.GetInstalledVoices() | Where-Object { $_.Enabled }
Write-Host "Found $($voices.Count) installed voices."

$posPhrases = @("friday", "hey friday", "ok friday", "hello friday", "friday assistant")

$negPhrases = @(
    "freeday", "free day", "fried day", "fry day", "fly day", "pry day", "play day", "gray day",
    "friend", "friendly", "friends", "fright", "frequent", "freedom", "freezer",
    "fire", "fire day", "find day", "fine day", "fight", "fighter", "frida", "freddy",
    "hello", "hi", "hey", "ok", "assistant", "phone", "google", "siri", "alexa", "bixby",
    "monday", "tuesday", "wednesday", "thursday", "saturday", "sunday",
    "today", "yesterday", "tomorrow", "tonight", "morning", "afternoon", "evening", "night",
    "volume", "music", "play", "stop", "pause", "open", "close", "lock", "unlock"
)

# Rates: -3 (slow), 0 (normal), 3 (fast)
$rates = @(-3, 0, 3)
# Volumes: 80, 100
$volumes = @(80, 100)

Write-Host "Generating positive samples..."
$posCount = 0
foreach ($voice in $voices) {
    $speak.SelectVoice($voice.VoiceInfo.Name)
    foreach ($rate in $rates) {
        $speak.Rate = $rate
        foreach ($vol in $volumes) {
            $speak.Volume = $vol
            foreach ($phrase in $posPhrases) {
                $path = Join-Path $posDir "pos_$posCount.wav"
                $speak.SetOutputToWaveFile($path)
                $speak.Speak($phrase)
                $speak.SetOutputToNull()
                $posCount++
            }
        }
    }
}
Write-Host "Generated $posCount positive files."

Write-Host "Generating negative samples..."
$negCount = 0
$negRates = @(-2, 2)
foreach ($voice in $voices) {
    $speak.SelectVoice($voice.VoiceInfo.Name)
    foreach ($rate in $negRates) {
        $speak.Rate = $rate
        foreach ($vol in $volumes) {
            $speak.Volume = $vol
            foreach ($phrase in $negPhrases) {
                $path = Join-Path $negDir "neg_$negCount.wav"
                $speak.SetOutputToWaveFile($path)
                $speak.Speak($phrase)
                $speak.SetOutputToNull()
                $negCount++
            }
        }
    }
}
Write-Host "Generated $negCount negative files."
$speak.Dispose()
Write-Host "Dataset generation completed successfully."
