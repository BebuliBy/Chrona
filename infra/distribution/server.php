<?php
declare(strict_types=1);
// Static read-only publisher. Never return false to the PHP built-in server:
// no filesystem/static fallback, upload, application includes or directory listing.
const DATA = '/published';
function failResponse(int $status, string $code): never {
    http_response_code($status);
    header('Content-Type: application/json');
    header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff');
    if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'HEAD') {
        echo json_encode(['error' => $code], JSON_UNESCAPED_SLASHES);
    }
    exit;
}
function regular(string $path): bool {
    return !is_link($path) && is_file($path) && realpath($path) === $path;
}
function manifestAt(string $path): array {
    if (!regular($path) || filesize($path) > 16384) failResponse(503, 'NOT_PUBLISHED');
    $bytes = file_get_contents($path);
    $m = json_decode($bytes, true, 16, JSON_THROW_ON_ERROR);
    $keys = ['schemaVersion','versionCode','versionName','apkPath','sha256','sizeBytes','packageName','minSdk','signingCertificateSha256','mandatory'];
    if (!is_array($m) || count($m) !== count($keys) || array_diff($keys, array_keys($m)) ||
        $m['schemaVersion'] !== 1 || $m['mandatory'] !== true ||
        !is_int($m['versionCode']) || $m['versionCode'] < 1 || $m['versionCode'] > 2147483647 ||
        !is_int($m['sizeBytes']) || $m['sizeBytes'] < 1 || !is_int($m['minSdk']) || $m['minSdk'] < 1 ||
        !is_string($m['versionName']) || strlen($m['versionName']) < 1 || strlen($m['versionName']) > 128 ||
        !is_string($m['packageName']) || !preg_match('/\A[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+\z/', $m['packageName']) ||
        !is_string($m['apkPath']) || !preg_match('/\Areleases\/Chrona-([1-9][0-9]{0,9})\.apk\z/', $m['apkPath'], $matches) ||
        (int)$matches[1] !== $m['versionCode'] ||
        !is_string($m['sha256']) || !preg_match('/\A[a-f0-9]{64}\z/', $m['sha256']) ||
        !is_string($m['signingCertificateSha256']) || !preg_match('/\A[a-f0-9]{64}\z/', $m['signingCertificateSha256'])) {
        failResponse(503, 'INVALID_PUBLICATION');
    }
    $apk = DATA . '/' . $m['apkPath'];
    if (!regular($apk) || filesize($apk) !== $m['sizeBytes']) failResponse(503, 'INVALID_PUBLICATION');
    return [$m, $bytes];
}
try {
    $method = $_SERVER['REQUEST_METHOD'] ?? '';
    if ($method !== 'GET' && $method !== 'HEAD') {
        header('Allow: GET, HEAD');
        failResponse(405, 'METHOD_NOT_ALLOWED');
    }
    $uri = $_SERVER['REQUEST_URI'] ?? '';
    if (str_contains($uri, '?') || str_contains($uri, '%') || str_contains($uri, "\\")) failResponse(404, 'NOT_FOUND');
    $path = parse_url($uri, PHP_URL_PATH);
    if ($path !== $uri) failResponse(404, 'NOT_FOUND');
    header('X-Content-Type-Options: nosniff');
    header('Referrer-Policy: no-referrer');
    if ($path === '/') {
        header('Content-Type: text/html; charset=utf-8');
        header('Cache-Control: no-store');
        header("Content-Security-Policy: default-src 'none'; base-uri 'none'; frame-ancestors 'none'");
        if ($method === 'GET') echo '<!doctype html><meta charset="utf-8"><title>Chrona</title><h1>Chrona</h1><a href="latest.apk">Скачать APK</a>';
        exit;
    }
    if ($path !== '/latest.json' && $path !== '/latest.apk' && !preg_match('/\A\/releases\/Chrona-[1-9][0-9]{0,9}\.apk\z/', (string)$path)) failResponse(404, 'NOT_FOUND');
    [$manifest, $json] = manifestAt(DATA . '/latest.json');
    if ($path === '/latest.json') {
        header('Content-Type: application/json; charset=utf-8');
        header('Cache-Control: no-store, max-age=0');
        header('Content-Length: ' . strlen($json));
        if ($method === 'GET') echo $json;
        exit;
    }
    $relative = $path === '/latest.apk' ? $manifest['apkPath'] : substr((string)$path, 1);
    if ($path !== '/latest.apk') {
        $sidecar = DATA . '/' . substr($relative, 0, -4) . '.json';
        if (!regular($sidecar) || !regular(DATA . '/' . $relative)) failResponse(404, 'NOT_FOUND');
        [$release, $ignored] = manifestAt($sidecar);
        if ($release['apkPath'] !== $relative || $release['packageName'] !== $manifest['packageName'] ||
            $release['signingCertificateSha256'] !== $manifest['signingCertificateSha256']) failResponse(404, 'NOT_FOUND');
    }
    $file = DATA . '/' . $relative;
    if (!regular($file)) failResponse(404, 'NOT_FOUND');
    $stream = fopen($file, 'rb');
    if ($stream === false) failResponse(503, 'READ_FAILED');
    $stat = fstat($stream);
    header('Content-Type: application/vnd.android.package-archive');
    header('Content-Disposition: attachment; filename="' . basename($relative) . '"');
    header('Content-Length: ' . $stat['size']);
    header('Accept-Ranges: none');
    header('Cache-Control: ' . ($path === '/latest.apk' ? 'no-store, max-age=0' : 'public, max-age=31536000, immutable'));
    if ($relative === $manifest['apkPath']) header('ETag: "' . $manifest['sha256'] . '"');
    if ($method === 'GET') fpassthru($stream);
    fclose($stream);
} catch (Throwable $ignored) {
    // No raw URL/path/tool/certificate/body exception logs.
    failResponse(503, 'DISTRIBUTION_NOT_READY');
}
