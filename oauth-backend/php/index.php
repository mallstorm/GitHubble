<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['error' => 'method_not_allowed']);
    exit;
}

$clientId = getenv('GITHUB_CLIENT_ID') ?: '';
$clientSecret = getenv('GITHUB_CLIENT_SECRET') ?: '';
$allowedRedirect = getenv('GITHUB_ALLOWED_REDIRECT_URI') ?: 'githubble://oauth/callback';

if ($clientId === '' || $clientSecret === '') {
    http_response_code(503);
    echo json_encode(['error' => 'oauth_backend_not_configured']);
    exit;
}

$data = json_decode(file_get_contents('php://input') ?: '', true);
if (!is_array($data)) {
    http_response_code(400);
    echo json_encode(['error' => 'invalid_json']);
    exit;
}

$code = trim((string)($data['code'] ?? ''));
$verifier = trim((string)($data['code_verifier'] ?? ''));
$redirectUri = trim((string)($data['redirect_uri'] ?? ''));

if ($code === '' || $verifier === '' || $redirectUri === '') {
    http_response_code(400);
    echo json_encode(['error' => 'missing_parameters']);
    exit;
}
if (!hash_equals($allowedRedirect, $redirectUri)) {
    http_response_code(400);
    echo json_encode(['error' => 'redirect_uri_not_allowed']);
    exit;
}

$post = http_build_query([
    'client_id' => $clientId,
    'client_secret' => $clientSecret,
    'code' => $code,
    'redirect_uri' => $redirectUri,
    'code_verifier' => $verifier,
]);

$ch = curl_init('https://github.com/login/oauth/access_token');
curl_setopt_array($ch, [
    CURLOPT_POST => true,
    CURLOPT_POSTFIELDS => $post,
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_CONNECTTIMEOUT => 10,
    CURLOPT_TIMEOUT => 25,
    CURLOPT_HTTPHEADER => [
        'Accept: application/json',
        'Content-Type: application/x-www-form-urlencoded',
        'User-Agent: GitHubble-OAuth-Backend',
    ],
]);
$response = curl_exec($ch);
$status = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
$error = curl_error($ch);
curl_close($ch);

if ($response === false || $error !== '') {
    http_response_code(502);
    echo json_encode(['error' => 'github_unreachable']);
    exit;
}

$github = json_decode($response, true);
if (!is_array($github) || $status < 200 || $status >= 300 || empty($github['access_token'])) {
    http_response_code(401);
    echo json_encode([
        'error' => (string)($github['error'] ?? 'token_exchange_failed'),
        'error_description' => (string)($github['error_description'] ?? ''),
    ]);
    exit;
}

$out = [
    'access_token' => (string)$github['access_token'],
    'token_type' => (string)($github['token_type'] ?? 'bearer'),
    'scope' => (string)($github['scope'] ?? ''),
];
if (!empty($github['refresh_token'])) $out['refresh_token'] = (string)$github['refresh_token'];
if (isset($github['expires_in'])) $out['expires_in'] = (int)$github['expires_in'];
if (isset($github['refresh_token_expires_in'])) $out['refresh_token_expires_in'] = (int)$github['refresh_token_expires_in'];

echo json_encode($out, JSON_UNESCAPED_SLASHES);
