-- 群聊协作模式：房间复用 chat_sessions（isGroupChat=1），成员发言落 agent_messages（USER+senderName），
-- 定时任务（Routines）建独立表。
ALTER TABLE chat_sessions ADD COLUMN presetName TEXT DEFAULT NULL;
ALTER TABLE chat_sessions ADD COLUMN isGroupChat INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_sessions ADD COLUMN groupMembersJson TEXT DEFAULT NULL;
ALTER TABLE agent_messages ADD COLUMN senderName TEXT DEFAULT NULL;

CREATE TABLE IF NOT EXISTS group_chat_routines (
    id TEXT PRIMARY KEY NOT NULL,
    roomId TEXT NOT NULL,
    memberKey TEXT NOT NULL,
    schedule TEXT NOT NULL,
    instruction TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    lastRunAt INTEGER
);

CREATE INDEX IF NOT EXISTS idx_group_routines_room ON group_chat_routines(roomId);
