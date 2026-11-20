/** 页面与插件进程 SDK 共用的基础协议类型。 */

export type JsonPrimitive = string | number | boolean | null;

/** 宿主与插件之间可传输的 JSON 值。 */
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };

export type JsonObject = { [key: string]: JsonValue };
