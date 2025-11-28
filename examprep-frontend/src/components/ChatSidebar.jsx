import { useState } from 'react';
import { chatAPI } from '../services/api';

export default function ChatSidebar({ chats, currentChat, onSelectChat, onCreateChat, onDeleteChat }) {
  const [editingChat, setEditingChat] = useState(null);
  const [newTitle, setNewTitle] = useState('');

  const handleEdit = (chat) => {
    setEditingChat(chat.id);
    setNewTitle(chat.title);
  };

  const handleSave = async (chatId) => {
    try {
      await chatAPI.update(chatId, { title: newTitle });
      setEditingChat(null);
      // Reload chats or update locally
      window.location.reload(); // Simple refresh for now
    } catch (error) {
      console.error('Failed to update chat:', error);
      setEditingChat(null);
    }
  };

  return (
    <div className="flex flex-col h-full">
      <div className="p-4 border-b border-gray-700 flex-shrink-0">
        <button
          onClick={onCreateChat}
          className="w-full px-4 py-2 bg-indigo-600 text-white rounded-md hover:bg-indigo-700"
        >
          + New Chat
        </button>
      </div>
      <div className="flex-1 overflow-y-auto min-h-0">
        {chats.map((chat) => (
          <div
            key={chat.id}
            className={`p-3 border-b cursor-pointer hover:bg-gray-50 w-full ${
              currentChat?.id === chat.id ? 'bg-indigo-50' : ''
            }`}
            onClick={() => onSelectChat(chat)}
          >
            {editingChat === chat.id ? (
              <input
                type="text"
                value={newTitle}
                onChange={(e) => setNewTitle(e.target.value)}
                onBlur={() => handleSave(chat.id)}
                onKeyPress={(e) => {
                  if (e.key === 'Enter') {
                    handleSave(chat.id);
                  }
                }}
                className="w-full px-2 py-1 border rounded"
                autoFocus
              />
            ) : (
              <div className="w-full">
                <div className="flex justify-between items-start gap-2 w-full">
                  <span className="text-sm font-medium text-gray-900 flex-1 break-words min-w-0" style={{ wordBreak: 'break-word', overflowWrap: 'anywhere' }}>
                    {chat.title}
                  </span>
                  <div className="flex space-x-1 flex-shrink-0 ml-2">
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        handleEdit(chat);
                      }}
                      className="text-gray-400 hover:text-gray-600"
                      title="Edit chat title"
                    >
                      ✏️
                    </button>
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        if (window.confirm('Delete this chat and all its files?')) {
                          onDeleteChat(chat.id);
                        }
                      }}
                      className="text-red-400 hover:text-red-600"
                      title="Delete chat"
                    >
                      🗑️
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

