-- 移除已废弃的 userAgent 列。
--
-- 该列由迁移 40 引入，但提交 04385620（「提供商支持自定义请求头与脚本参数」）用
-- customHeaders + scriptParams 取代它时只改了 Entity、没有写迁移删除列。结果：
-- 从 rc7~rc10 等已发布版本升级的用户，库里 userAgent 物理存在而 Entity 不认，
-- Room 校验「表里多出 userAgent」失败、onUpgrade 抛异常，App 启动即崩
-- （IllegalStateException: Migration didn't properly handle: ai_providers）。
--
-- minSdk 26 对应用户态 SQLite 3.18，无 ALTER TABLE ... DROP COLUMN，
-- 故按迁移 25/32 的先例重建表。
--
-- 注意：INSERT ... SELECT 的列清单不含 userAgent，因而本迁移对两种源状态都成立——
-- 无论旧表有没有 userAgent（老用户有、全新安装的 53 库没有），结果都收敛到目标列集，
-- 重复执行也不会报 duplicate column。
CREATE TABLE ai_providers_new (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    apiKey TEXT NOT NULL,
    multiKeyEnabled INTEGER NOT NULL,
    apiKeys TEXT NOT NULL,
    keyRotationStrategy TEXT NOT NULL,
    keyFailoverThreshold INTEGER NOT NULL,
    keyCooldownMinutes INTEGER NOT NULL,
    keySwitchStatusCodes TEXT NOT NULL,
    baseUrl TEXT NOT NULL,
    defaultModel TEXT NOT NULL,
    models TEXT NOT NULL,
    selectedModel TEXT NOT NULL,
    isEnabled INTEGER NOT NULL,
    useFullUrl INTEGER NOT NULL,
    useResponseApi INTEGER NOT NULL,
    anthropicCacheBreakpoints INTEGER NOT NULL,
    openaiChatCacheKey INTEGER NOT NULL,
    balanceScriptPath TEXT NOT NULL,
    balanceRefreshInterval INTEGER NOT NULL,
    customHeaders TEXT NOT NULL,
    sortOrder INTEGER NOT NULL,
    proxyEnabled INTEGER NOT NULL,
    proxyType TEXT NOT NULL,
    proxyHost TEXT NOT NULL,
    proxyPort INTEGER NOT NULL,
    proxyUsername TEXT NOT NULL,
    proxyPassword TEXT NOT NULL,
    scriptParams TEXT NOT NULL
);

INSERT INTO ai_providers_new (
    id, name, type, apiKey, multiKeyEnabled, apiKeys, keyRotationStrategy,
    keyFailoverThreshold, keyCooldownMinutes, keySwitchStatusCodes, baseUrl,
    defaultModel, models, selectedModel, isEnabled, useFullUrl, useResponseApi,
    anthropicCacheBreakpoints, openaiChatCacheKey, balanceScriptPath,
    balanceRefreshInterval, customHeaders, sortOrder, proxyEnabled, proxyType,
    proxyHost, proxyPort, proxyUsername, proxyPassword, scriptParams
)
SELECT
    id, name, type, apiKey, multiKeyEnabled, apiKeys, keyRotationStrategy,
    keyFailoverThreshold, keyCooldownMinutes, keySwitchStatusCodes, baseUrl,
    defaultModel, models, selectedModel, isEnabled, useFullUrl, useResponseApi,
    anthropicCacheBreakpoints, openaiChatCacheKey, balanceScriptPath,
    balanceRefreshInterval, customHeaders, sortOrder, proxyEnabled, proxyType,
    proxyHost, proxyPort, proxyUsername, proxyPassword, scriptParams
FROM ai_providers;

DROP TABLE ai_providers;
ALTER TABLE ai_providers_new RENAME TO ai_providers;
