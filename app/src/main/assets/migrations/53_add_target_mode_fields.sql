-- 53: 新增 TARGET 目标驱动模式的会话级字段。
-- goalStatement：目标声明文本；goalTerminationReason：终止原因（ACHIEVED/FAILED/STEP_LIMIT/INTERRUPTED）。
-- goalStepCount / goalFailCount：TARGET 执行期间的步数与连续失败计数。现有会话默认 NULL / 0，向后兼容。

ALTER TABLE chat_sessions ADD COLUMN goalStatement TEXT;
ALTER TABLE chat_sessions ADD COLUMN goalTerminationReason TEXT;
ALTER TABLE chat_sessions ADD COLUMN goalStepCount INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_sessions ADD COLUMN goalFailCount INTEGER NOT NULL DEFAULT 0;
