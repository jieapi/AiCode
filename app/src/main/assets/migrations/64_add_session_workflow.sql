-- 会话级工作流绑定：支持将特定会话路由到指定工作流并保存其持久化状态
ALTER TABLE chat_sessions ADD COLUMN workflowId TEXT DEFAULT NULL;
ALTER TABLE chat_sessions ADD COLUMN workflowState TEXT DEFAULT NULL;
