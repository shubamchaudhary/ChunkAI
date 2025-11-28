import { useState, useEffect } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { chatAPI, documentAPI } from '../services/api';
import ChatSidebar from '../components/ChatSidebar';
import FileUpload from '../components/FileUpload';
import FileList from '../components/FileList';
import QueryInterface from '../components/QueryInterface';

export default function AppLayout() {
  const { user, logout } = useAuth();
  const [chats, setChats] = useState([]);
  const [currentChat, setCurrentChat] = useState(null);
  const [documents, setDocuments] = useState([]);
  const [selectedFile, setSelectedFile] = useState(null);
  const [loading, setLoading] = useState(true);
  const [showFiles, setShowFiles] = useState(false);

  useEffect(() => {
    loadChats();
  }, []);

  useEffect(() => {
    if (currentChat) {
      loadDocuments(currentChat.id);
    }
  }, [currentChat]);

  const loadChats = async () => {
    try {
      const response = await chatAPI.getAll({ page: 0, size: 100 });
      setChats(response.data.content || []);
      if (response.data.content && response.data.content.length > 0 && !currentChat) {
        setCurrentChat(response.data.content[0]);
      }
    } catch (error) {
      console.error('Failed to load chats:', error);
    } finally {
      setLoading(false);
    }
  };

  const loadDocuments = async (chatId) => {
    try {
      const response = await documentAPI.getAll({ chatId, page: 0, size: 100 });
      setDocuments(response.data.content || []);
    } catch (error) {
      console.error('Failed to load documents:', error);
    }
  };

  const createChat = async () => {
    try {
      const response = await chatAPI.create({ title: 'New Chat' });
      const newChat = response.data;
      setChats([newChat, ...chats]);
      setCurrentChat(newChat);
    } catch (error) {
      console.error('Failed to create chat:', error);
    }
  };

  const deleteChat = async (chatId) => {
    try {
      await chatAPI.delete(chatId);
      setChats(chats.filter(c => c.id !== chatId));
      if (currentChat?.id === chatId) {
        const remainingChats = chats.filter(c => c.id !== chatId);
        setCurrentChat(remainingChats.length > 0 ? remainingChats[0] : null);
      }
    } catch (error) {
      console.error('Failed to delete chat:', error);
    }
  };

  const handleFileUpload = async (files) => {
    if (!currentChat) {
      alert('Please select or create a chat first');
      return;
    }
    await loadDocuments(currentChat.id);
  };

  const handleFileDelete = async (documentId) => {
    if (!currentChat) return;
    try {
      await documentAPI.delete(documentId, currentChat.id);
      setDocuments(documents.filter(d => d.id !== documentId));
      if (selectedFile?.id === documentId) {
        setSelectedFile(null);
      }
    } catch (error) {
      console.error('Failed to delete file:', error);
      alert('Failed to delete file. Please try again.');
    }
  };

  if (loading) {
    return <div className="min-h-screen flex items-center justify-center">Loading...</div>;
  }

  return (
    <div className="min-h-screen bg-gray-50 flex h-screen overflow-hidden">
      {/* Left Sidebar - Chat List */}
      <div className="w-64 bg-gray-900 text-white flex flex-col flex-shrink-0 overflow-hidden">
        <div className="p-4 border-b border-gray-700 flex-shrink-0">
          <h1 className="text-xl font-bold">DeepDocAI</h1>
          <p className="text-sm text-gray-400 mt-1 truncate">{user?.email}</p>
        </div>
        <div className="flex-1 overflow-y-auto min-h-0">
          <ChatSidebar
            chats={chats}
            currentChat={currentChat}
            onSelectChat={setCurrentChat}
            onCreateChat={createChat}
            onDeleteChat={deleteChat}
          />
        </div>
        <div className="p-4 border-t border-gray-700 flex-shrink-0">
          <button
            onClick={logout}
            className="w-full px-4 py-2 text-sm font-medium text-white bg-red-600 rounded-md hover:bg-red-700"
          >
            Logout
          </button>
        </div>
      </div>

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col overflow-hidden">
        {currentChat ? (
          <>
            {/* Top Bar - File Upload Toggle & Upload Button */}
            <div className="bg-white border-b border-gray-200 px-4 py-2 flex items-center justify-between">
              <div className="flex items-center space-x-2">
                <button
                  onClick={() => setShowFiles(!showFiles)}
                  className="px-3 py-1.5 text-sm font-medium text-gray-700 bg-gray-100 rounded-md hover:bg-gray-200"
                >
                  {showFiles ? '📁 Hide Files' : '📁 Show Files'} ({documents.length})
                </button>
                {showFiles && (
                  <FileUpload chatId={currentChat.id} onUpload={handleFileUpload} />
                )}
              </div>
              <h2 className="text-lg font-semibold text-gray-800">{currentChat.title}</h2>
            </div>

            {/* Files Panel (Collapsible) */}
            {showFiles && (
              <div className="bg-white border-b border-gray-200 max-h-48 overflow-y-auto">
                <FileList
                  documents={documents}
                  onSelectFile={setSelectedFile}
                  onDeleteFile={handleFileDelete}
                  onDocumentsUpdate={() => loadDocuments(currentChat.id)}
                />
              </div>
            )}

            {/* Main Chat Area */}
            <div className="flex-1 flex flex-col overflow-hidden">
              <QueryInterface chatId={currentChat.id} documents={documents} />
            </div>
          </>
        ) : (
          <div className="flex-1 flex items-center justify-center">
            <div className="text-center">
              <p className="text-gray-500 mb-4">No chat selected</p>
              <button
                onClick={createChat}
                className="px-4 py-2 bg-indigo-600 text-white rounded-md hover:bg-indigo-700"
              >
                Create New Chat
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
