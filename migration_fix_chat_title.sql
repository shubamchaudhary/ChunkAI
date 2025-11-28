-- Migration script to fix chat title truncation issue
-- Run this script on your existing database to change VARCHAR(255) to TEXT

-- Alter the chats table to change title column from VARCHAR(255) to TEXT
ALTER TABLE chats ALTER COLUMN title TYPE TEXT;

-- Verify the change
SELECT column_name, data_type, character_maximum_length 
FROM information_schema.columns 
WHERE table_name = 'chats' AND column_name = 'title';

