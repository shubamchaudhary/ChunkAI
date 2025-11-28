import { useState, useEffect, useRef } from 'react';
import { queryAPI } from '../services/api';

export default function QueryInterface({ chatId, documents }) {
  const [question, setQuestion] = useState('');
  const [marks, setMarks] = useState('');
  const [useCrossChat, setUseCrossChat] = useState(false);
  const [loading, setLoading] = useState(false);
  const [messages, setMessages] = useState([]);
  const messagesEndRef = useRef(null);
  
  // Check if any documents are still processing
  const processingDocs = documents?.filter(doc => 
    doc.processingStatus === 'PENDING' || doc.processingStatus === 'PROCESSING'
  ) || [];
  
  const completedDocs = documents?.filter(doc => 
    doc.processingStatus === 'COMPLETED'
  ) || [];
  
  // Allow queries even without documents - system can use internet search
  const canQuery = processingDocs.length === 0;

  useEffect(() => {
    loadHistory();
  }, [chatId]);

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  const loadHistory = async () => {
    try {
      const response = await queryAPI.getHistory({ chatId, page: 0, size: 50 });
      const history = response.data.content || [];
      // Convert history to message format
      // History comes in descending order (newest first), so reverse to get chronological order
      const reversedHistory = [...history].reverse();
      const messageList = [];
      for (const item of reversedHistory) {
        messageList.push({ type: 'user', content: item.question });
        if (item.answer) {
          messageList.push({ type: 'assistant', content: item.answer });
        }
      }
      setMessages(messageList); // Already in correct order (oldest to newest)
    } catch (error) {
      console.error('Failed to load history:', error);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!question.trim() || loading || !canQuery) return;

    const userMessage = question;
    setQuestion('');
    setMessages(prev => [...prev, { type: 'user', content: userMessage }]);
    setLoading(true);

    try {
      const response = await queryAPI.query({
        question: userMessage,
        marks: marks ? parseInt(marks) : null,
        chatId,
        useCrossChat: useCrossChat || false, // Ensure boolean, not undefined
      });
      
      console.log('Query sent with:', { chatId, useCrossChat: useCrossChat || false });
      
      setMessages(prev => [...prev, { 
        type: 'assistant', 
        content: response.data.answer,
        sources: response.data.sources 
      }]);
      setMarks(''); // Clear marks after query
    } catch (error) {
      console.error('Query failed:', error);
      setMessages(prev => [...prev, { 
        type: 'assistant', 
        content: 'Error: ' + (error.response?.data?.message || error.message),
        error: true
      }]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="h-full flex flex-col bg-gray-50">
      {/* Messages Area */}
      <div className="flex-1 overflow-y-auto px-6 py-8">
        {messages.length === 0 ? (
          <div className="flex items-center justify-center h-full">
            <div className="text-center text-gray-500">
              {processingDocs.length > 0 ? (
                <div>
                  <p className="text-lg mb-2">⏳ Processing Documents</p>
                  <p className="text-sm">{processingDocs.length} document(s) still processing...</p>
                </div>
              ) : completedDocs.length === 0 && !useCrossChat ? (
                <div>
                  <p className="text-lg mb-2">📄 No Documents</p>
                  <p className="text-sm">Upload documents to start asking questions</p>
                </div>
              ) : (
                <div>
                  <p className="text-lg mb-2">💬 Start a Conversation</p>
                  <p className="text-sm">Ask questions about your documents</p>
                </div>
              )}
            </div>
          </div>
        ) : (
          <div className="max-w-4xl mx-auto space-y-8">
            {messages.map((msg, idx) => (
              <div
                key={idx}
                className="flex justify-start items-start space-x-4"
              >
                {/* Avatar/Icon */}
                <div className={`flex-shrink-0 w-8 h-8 rounded-full flex items-center justify-center text-sm font-semibold ${
                  msg.type === 'user'
                    ? 'bg-indigo-600 text-white'
                    : 'bg-indigo-100 text-indigo-700'
                }`}>
                  {msg.type === 'user' ? 'U' : 'D'}
                </div>
                
                {/* Message Content */}
                <div className="flex-1 min-w-0">
                  {/* Message Label */}
                  <div className="mb-1">
                    <span className={`text-xs font-medium ${
                      msg.type === 'user' ? 'text-indigo-600' : 'text-gray-600'
                    }`}>
                      {msg.type === 'user' ? 'You' : 'DeepDocAI'}
                    </span>
                  </div>
                  
                  {/* Message Bubble */}
                  <div
                    className={`rounded-lg px-5 py-4 shadow-sm ${
                      msg.type === 'user'
                        ? 'bg-white border border-indigo-200 text-gray-900'
                        : msg.error
                        ? 'bg-red-50 text-red-800 border border-red-200'
                        : 'bg-white border border-gray-200 text-gray-900'
                    }`}
                  >
                    <div className="prose prose-sm max-w-none">
                      <div className="text-gray-800 leading-relaxed whitespace-pre-wrap">
                        {msg.type === 'assistant' ? (
                          <div className="markdown-content">
                            {msg.content.split('\n').map((line, lineIdx) => {
                              // Handle headers
                              if (line.startsWith('### ')) {
                                return <h3 key={lineIdx} className="text-lg font-bold mt-4 mb-2 text-gray-900">{line.substring(4)}</h3>;
                              }
                              if (line.startsWith('## ')) {
                                return <h2 key={lineIdx} className="text-xl font-bold mt-5 mb-3 text-gray-900">{line.substring(3)}</h2>;
                              }
                              if (line.startsWith('# ')) {
                                return <h1 key={lineIdx} className="text-2xl font-bold mt-6 mb-4 text-gray-900">{line.substring(2)}</h1>;
                              }
                              // Handle bold text (**text**)
                              let processedLine = line;
                              const boldRegex = /\*\*([^*]+)\*\*/g;
                              const parts = [];
                              let lastIndex = 0;
                              let match;
                              while ((match = boldRegex.exec(line)) !== null) {
                                if (match.index > lastIndex) {
                                  parts.push({ text: line.substring(lastIndex, match.index), bold: false });
                                }
                                parts.push({ text: match[1], bold: true });
                                lastIndex = match.index + match[0].length;
                              }
                              if (lastIndex < line.length) {
                                parts.push({ text: line.substring(lastIndex), bold: false });
                              }
                              if (parts.length === 0) {
                                parts.push({ text: line, bold: false });
                              }
                              
                              // Handle bullet points
                              if (line.trim().startsWith('* ') || line.trim().startsWith('- ')) {
                                const bulletText = line.trim().substring(2);
                                return (
                                  <div key={lineIdx} className="ml-4 mb-1 flex items-start">
                                    <span className="text-indigo-500 mr-2 mt-1">•</span>
                                    <span>
                                      {parts.map((part, partIdx) => 
                                        part.bold ? (
                                          <strong key={partIdx} className="font-semibold text-gray-900">{part.text}</strong>
                                        ) : (
                                          <span key={partIdx}>{part.text}</span>
                                        )
                                      )}
                                    </span>
                                  </div>
                                );
                              }
                              
                              // Regular paragraph
                              if (line.trim()) {
                                return (
                                  <p key={lineIdx} className="mb-2">
                                    {parts.map((part, partIdx) => 
                                      part.bold ? (
                                        <strong key={partIdx} className="font-semibold text-gray-900">{part.text}</strong>
                                      ) : (
                                        <span key={partIdx}>{part.text}</span>
                                      )
                                    )}
                                  </p>
                                );
                              }
                              return <br key={lineIdx} />;
                            })}
                          </div>
                        ) : (
                          <div className="whitespace-pre-wrap">{msg.content}</div>
                        )}
                      </div>
                    </div>
                    
                    {msg.sources && msg.sources.length > 0 && (
                      <div className="mt-4 pt-4 border-t border-gray-200">
                        <p className="text-xs font-semibold text-gray-600 mb-2">Sources:</p>
                        <ul className="text-xs space-y-1.5 text-gray-600">
                          {msg.sources.map((source, sidx) => (
                            <li key={sidx} className="flex items-start">
                              <span className="text-indigo-500 mr-2">•</span>
                              <span>
                                {source.fileName}
                                {source.pageNumber && <span className="text-gray-500"> (Page {source.pageNumber})</span>}
                                {source.slideNumber && <span className="text-gray-500"> (Slide {source.slideNumber})</span>}
                              </span>
                            </li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            ))}
            {loading && (
              <div className="flex justify-start items-start space-x-4">
                <div className="flex-shrink-0 w-8 h-8 rounded-full bg-indigo-100 flex items-center justify-center text-sm font-semibold text-indigo-700">
                  D
                </div>
                <div className="flex-1">
                  <div className="mb-1">
                    <span className="text-xs font-medium text-gray-600">DeepDocAI</span>
                  </div>
                  <div className="bg-white border border-gray-200 rounded-lg px-5 py-4 shadow-sm">
                    <div className="flex space-x-2">
                      <div className="w-2 h-2 bg-indigo-400 rounded-full animate-bounce"></div>
                      <div className="w-2 h-2 bg-indigo-400 rounded-full animate-bounce" style={{ animationDelay: '0.2s' }}></div>
                      <div className="w-2 h-2 bg-indigo-400 rounded-full animate-bounce" style={{ animationDelay: '0.4s' }}></div>
                    </div>
                  </div>
                </div>
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>
        )}
      </div>

      {/* Input Area */}
      <div className="border-t border-gray-200 bg-white shadow-lg">
        {processingDocs.length > 0 && (
          <div className="px-6 py-3 bg-yellow-50 border-b border-yellow-200">
            <p className="text-sm text-yellow-800 flex items-center">
              <span className="mr-2">⏳</span>
              {processingDocs.length} document(s) still processing. Please wait...
            </p>
          </div>
        )}
        
        <div className="px-6 py-4">
          <form onSubmit={handleSubmit} className="space-y-3">
            <div className="flex items-end space-x-3">
              <div className="flex-1">
                <textarea
                  value={question}
                  onChange={(e) => setQuestion(e.target.value)}
                  placeholder={canQuery ? "Ask a question about your documents..." : "Wait for documents to process..."}
                  className="w-full px-4 py-3 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent disabled:bg-gray-100 disabled:cursor-not-allowed resize-none text-gray-900 placeholder-gray-400"
                  rows="2"
                  disabled={loading || !canQuery}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      handleSubmit(e);
                    }
                  }}
                />
              </div>
              <button
                type="submit"
                disabled={loading || !question.trim() || !canQuery}
                className="px-6 py-3 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed font-medium shadow-sm transition-colors"
              >
                {loading ? (
                  <span className="flex items-center">
                    <svg className="animate-spin -ml-1 mr-2 h-4 w-4 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                    </svg>
                    Sending...
                  </span>
                ) : (
                  'Send'
                )}
              </button>
            </div>
            <div className="flex items-center justify-between text-sm">
              <div className="flex items-center space-x-4">
                <div className="flex items-center space-x-2">
                  <label className="text-gray-600 text-xs">Marks:</label>
                  <input
                    type="number"
                    value={marks}
                    onChange={(e) => setMarks(e.target.value)}
                    placeholder="Optional"
                    className="w-20 px-2 py-1.5 border border-gray-300 rounded focus:outline-none focus:ring-1 focus:ring-indigo-500 disabled:bg-gray-100 text-sm"
                    disabled={!canQuery}
                  />
                </div>
                <label className="flex items-center space-x-2 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={useCrossChat}
                    onChange={(e) => setUseCrossChat(e.target.checked)}
                    className="rounded border-gray-300 text-indigo-600 focus:ring-indigo-500"
                    disabled={!canQuery}
                  />
                  <span className="text-gray-600 text-sm">Use other chats</span>
                </label>
              </div>
              {completedDocs.length > 0 && (
                <span className="text-gray-500 text-xs">
                  {completedDocs.length} document(s) ready
                </span>
              )}
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
