param(
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [float]$Radius = 50.0,
    [int]$Latitudes = 36,
    [int]$Longitudes = 72
)

# Geometry follows AndroidX Media3 Projection.createEquirectangular().
# Copyright 2018 The Android Open Source Project, Apache License 2.0.
$culture = [System.Globalization.CultureInfo]::InvariantCulture
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("# AndroidX Media3 equirectangular sphere")
$lines.Add("# Generated with 36 latitude and 72 longitude segments")

for ($latitude = 0; $latitude -le $Latitudes; $latitude++) {
    $phi = [Math]::PI * $latitude / $Latitudes - [Math]::PI / 2.0
    for ($longitude = 0; $longitude -le $Longitudes; $longitude++) {
        $theta = 2.0 * [Math]::PI * $longitude / $Longitudes
        $x = -$Radius * [Math]::Sin($theta) * [Math]::Cos($phi)
        $y = $Radius * [Math]::Sin($phi)
        $z = $Radius * [Math]::Cos($theta) * [Math]::Cos($phi)
        $u = [double]$longitude / $Longitudes
        $v = [double]$latitude / $Latitudes
        $xText = $x.ToString("R", $culture)
        $yText = $y.ToString("R", $culture)
        $zText = $z.ToString("R", $culture)
        $uText = $u.ToString("R", $culture)
        $vText = $v.ToString("R", $culture)
        $lines.Add("v $xText $yText $zText")
        $lines.Add("vt $uText $vText")
    }
}

for ($latitude = 0; $latitude -lt $Latitudes; $latitude++) {
    for ($longitude = 0; $longitude -lt $Longitudes; $longitude++) {
        $topLeft = $latitude * ($Longitudes + 1) + $longitude + 1
        $topRight = $topLeft + 1
        $bottomLeft = ($latitude + 1) * ($Longitudes + 1) + $longitude + 1
        $bottomRight = $bottomLeft + 1

        # Inward-facing winding.
        $lines.Add("f $topLeft/$topLeft $topRight/$topRight $bottomLeft/$bottomLeft")
        $lines.Add("f $topRight/$topRight $bottomRight/$bottomRight $bottomLeft/$bottomLeft")
    }
}

[System.IO.File]::WriteAllLines($OutputPath, $lines)
