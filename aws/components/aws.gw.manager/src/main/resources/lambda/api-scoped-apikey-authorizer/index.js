'use strict';

const {
    APIGatewayClient,
    GetApiKeysCommand,
    GetTagsCommand,
} = require('@aws-sdk/client-api-gateway');

const TAG_API_ID = 'wso2:api-id';
const API_KEY_HEADER = 'x-api-key';

const DEFAULT_CACHE_TTL_SECONDS = parsePositiveInt(process.env.CACHE_TTL_SECONDS, 60);
const LOG_LEVEL = (process.env.LOG_LEVEL || 'info').toLowerCase();

// Runtime-local caches (warm Lambda invocations only)
const apiKeyValueCache = new Map();
const apiKeyTagCache = new Map();
const clientByRegion = new Map();

exports.handler = async (event) => {
    try {
        const methodArn = event && event.methodArn;
        if (!methodArn) {
            return buildDenyPolicy('anonymous', '*', { reason: 'MISSING_METHOD_ARN' });
        }

        const parsedArn = parseMethodArn(methodArn);
        if (!parsedArn) {
            return buildDenyPolicy('anonymous', methodArn, { reason: 'INVALID_METHOD_ARN' });
        }

        const apiKeyValue = extractApiKeyValue(event);
        if (!apiKeyValue) {
            return buildDenyPolicy('anonymous', methodArn, { reason: 'MISSING_API_KEY' });
        }

        const keyMetadata = await findApiKeyByValue(apiKeyValue, parsedArn.region);
        if (!keyMetadata) {
            return buildDenyPolicy('unknown', methodArn, { reason: 'INVALID_API_KEY' });
        }

        if (keyMetadata.enabled === false) {
            return buildDenyPolicy(keyMetadata.id, methodArn, { reason: 'DISABLED_API_KEY' });
        }

        const tags = await getApiKeyTags(keyMetadata.id, parsedArn.region);
        const scopeDecision = evaluateScope(tags, parsedArn.apiId);

        if (!scopeDecision.allowed) {
            return buildDenyPolicy(keyMetadata.id, methodArn, {
                reason: scopeDecision.reason,
                apiKeyId: keyMetadata.id,
                targetApiId: parsedArn.apiId,
            });
        }

        return buildAllowPolicy(keyMetadata.id, methodArn, {
            reason: 'AUTHORIZED',
            apiKeyId: keyMetadata.id,
            targetApiId: parsedArn.apiId,
        });
    } catch (error) {
        log('error', 'Authorizer execution failed', { message: error && error.message });
        return buildDenyPolicy('error', (event && event.methodArn) || '*', { reason: 'INTERNAL_ERROR' });
    }
};

function extractApiKeyValue(event) {
    if (!event) {
        return null;
    }

    const headers = event.headers || {};
    for (const [name, value] of Object.entries(headers)) {
        if (name && name.toLowerCase() === API_KEY_HEADER) {
            return sanitizeHeaderValue(value);
        }
    }

    const multiValueHeaders = event.multiValueHeaders || {};
    for (const [name, values] of Object.entries(multiValueHeaders)) {
        if (name && name.toLowerCase() === API_KEY_HEADER && Array.isArray(values) && values.length > 0) {
            return sanitizeHeaderValue(values[0]);
        }
    }

    return null;
}

function sanitizeHeaderValue(value) {
    if (typeof value !== 'string') {
        return null;
    }
    const normalized = value.trim();
    return normalized.length > 0 ? normalized : null;
}

function parseMethodArn(methodArn) {
    // arn:aws:execute-api:{region}:{accountId}:{apiId}/{stage}/{method}/{resourcePath}
    if (typeof methodArn !== 'string' || !methodArn.startsWith('arn:')) {
        return null;
    }

    const arnParts = methodArn.split(':');
    if (arnParts.length < 6) {
        return null;
    }

    const region = arnParts[3];
    const resourcePart = arnParts[5];
    const apiId = resourcePart ? resourcePart.split('/')[0] : null;

    if (!region || !apiId) {
        return null;
    }
    return { region, apiId };
}

async function findApiKeyByValue(apiKeyValue, region) {
    const cacheKey = `${region}|${apiKeyValue}`;
    const cached = readCache(apiKeyValueCache, cacheKey);
    if (cached) {
        return cached;
    }

    const client = getApiGatewayClient(region);
    let position;

    do {
        const response = await client.send(new GetApiKeysCommand({
            limit: 500,
            position,
            includeValues: true,
        }));

        const items = response && response.items ? response.items : [];
        for (const item of items) {
            if (item && item.value === apiKeyValue) {
                const keyMetadata = {
                    id: item.id,
                    enabled: item.enabled !== false,
                    name: item.name || '',
                };
                writeCache(apiKeyValueCache, cacheKey, keyMetadata);
                return keyMetadata;
            }
        }

        position = response && response.position ? response.position : null;
    } while (position);

    log('debug', 'API key value was not found', { apiKeyValueTail: maskTail(apiKeyValue) });
    return null;
}

async function getApiKeyTags(apiKeyId, region) {
    const cacheKey = `${region}|${apiKeyId}`;
    const cached = readCache(apiKeyTagCache, cacheKey);
    if (cached) {
        return cached;
    }

    const client = getApiGatewayClient(region);
    const resourceArn = `arn:aws:apigateway:${region}::/apikeys/${apiKeyId}`;
    let position;
    const mergedTags = {};

    do {
        const response = await client.send(new GetTagsCommand({
            resourceArn,
            position,
            limit: 500,
        }));

        Object.assign(mergedTags, (response && response.tags) || {});
        position = response && response.position ? response.position : null;
    } while (position);

    writeCache(apiKeyTagCache, cacheKey, mergedTags);
    return mergedTags;
}

function evaluateScope(tags, targetApiId) {
    const taggedApiId = sanitizeHeaderValue(tags ? tags[TAG_API_ID] : null);
    const normalizedTargetApiId = String(targetApiId || '').toLowerCase();
    const normalizedTaggedApiId = String(taggedApiId || '').toLowerCase();

    if (!normalizedTaggedApiId) {
        return { allowed: false, reason: 'MISSING_API_SCOPE_TAG' };
    }

    const allowed = normalizedTaggedApiId === normalizedTargetApiId;
    return {
        allowed,
        reason: allowed ? 'SCOPE_MATCH' : 'API_SCOPE_MISMATCH',
    };
}

function getApiGatewayClient(region) {
    if (clientByRegion.has(region)) {
        return clientByRegion.get(region);
    }
    const client = new APIGatewayClient({ region });
    clientByRegion.set(region, client);
    return client;
}

function buildAllowPolicy(principalId, methodArn, context) {
    return buildPolicy(principalId || 'authorized', 'Allow', methodArn, context);
}

function buildDenyPolicy(principalId, methodArn, context) {
    return buildPolicy(principalId || 'denied', 'Deny', methodArn, context);
}

function buildPolicy(principalId, effect, resource, context) {
    return {
        principalId,
        policyDocument: {
            Version: '2012-10-17',
            Statement: [
                {
                    Action: 'execute-api:Invoke',
                    Effect: effect,
                    Resource: resource,
                },
            ],
        },
        context: stringifyContext(context),
    };
}

function stringifyContext(context) {
    const result = {};
    const source = context || {};
    for (const [key, value] of Object.entries(source)) {
        if (value === null || value === undefined) {
            continue;
        }
        result[key] = String(value);
    }
    return result;
}

function parsePositiveInt(value, fallback) {
    const parsed = Number.parseInt(value, 10);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function readCache(cache, key) {
    const now = Date.now();
    const entry = cache.get(key);
    if (!entry) {
        return null;
    }
    if (entry.expiresAt <= now) {
        cache.delete(key);
        return null;
    }
    return entry.value;
}

function writeCache(cache, key, value) {
    const expiresAt = Date.now() + (DEFAULT_CACHE_TTL_SECONDS * 1000);
    cache.set(key, { value, expiresAt });
}

function maskTail(value) {
    if (typeof value !== 'string' || value.length < 4) {
        return '***';
    }
    return `***${value.slice(-4)}`;
}

function log(level, message, details) {
    const severity = toSeverity(level);
    const current = toSeverity(LOG_LEVEL);
    if (severity < current) {
        return;
    }
    const payload = details ? ` ${JSON.stringify(details)}` : '';
    // eslint-disable-next-line no-console
    console.log(`[${level.toUpperCase()}] ${message}${payload}`);
}

function toSeverity(level) {
    switch ((level || '').toLowerCase()) {
        case 'debug':
            return 10;
        case 'info':
            return 20;
        case 'warn':
            return 30;
        case 'error':
            return 40;
        default:
            return 20;
    }
}
