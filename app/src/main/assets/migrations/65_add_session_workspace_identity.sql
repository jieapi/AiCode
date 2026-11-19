ALTER TABLE chat_sessions ADD COLUMN backendInstanceId TEXT;
ALTER TABLE chat_sessions ADD COLUMN workspaceId TEXT;
CREATE INDEX IF NOT EXISTS index_chat_sessions_backendInstanceId_workspaceId ON chat_sessions (backendInstanceId, workspaceId);
