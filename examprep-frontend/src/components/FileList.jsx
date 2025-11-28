import { useEffect, useState } from 'react';
import { documentAPI } from '../services/api';

const STATUS_COLORS = {
  PENDING: 'bg-yellow-100 text-yellow-800',
  PROCESSING: 'bg-blue-100 text-blue-800',
  COMPLETED: 'bg-green-100 text-green-800',
  FAILED: 'bg-red-100 text-red-800',
};

export default function FileList({ documents, onSelectFile, onDeleteFile, onDocumentsUpdate }) {
  useEffect(() => {
    // Check if there are any processing documents
    const processingDocs = documents.filter(
      d => d.processingStatus === 'PENDING' || d.processingStatus === 'PROCESSING'
    );
    
    if (processingDocs.length === 0 || !onDocumentsUpdate) {
      return;
    }

    // Poll for processing status every 2 seconds
    const interval = setInterval(async () => {
      try {
        await onDocumentsUpdate();
      } catch (error) {
        console.error('Failed to update documents:', error);
      }
    }, 2000);

    return () => clearInterval(interval);
  }, [documents, onDocumentsUpdate]);

  return (
    <div className="space-y-2">
      <h3 className="text-lg font-semibold mb-4">Documents ({documents.length})</h3>
      {documents.length === 0 ? (
        <p className="text-gray-500 text-center py-8">No documents uploaded yet</p>
      ) : (
        <div className="space-y-2">
          {documents.map((doc) => (
            <div
              key={doc.id}
              className="flex items-center justify-between p-3 bg-white rounded-lg shadow-sm hover:shadow-md cursor-pointer border"
              onClick={() => onSelectFile(doc)}
            >
              <div className="flex-1 min-w-0">
                <div className="flex items-center space-x-2">
                  <span className="font-medium text-gray-900 truncate">
                    {doc.fileName}
                  </span>
                  <span className={`px-2 py-1 text-xs rounded ${STATUS_COLORS[doc.processingStatus] || STATUS_COLORS.PENDING}`}>
                    {doc.processingStatus}
                  </span>
                </div>
                <div className="text-sm text-gray-500 mt-1">
                  {(doc.fileSizeBytes / 1024 / 1024).toFixed(2)} MB
                  {doc.totalPages && ` • ${doc.totalPages} pages`}
                  {doc.totalChunks && ` • ${doc.totalChunks} chunks`}
                </div>
              </div>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  if (window.confirm('Delete this file?')) {
                    onDeleteFile(doc.id);
                  }
                }}
                className="ml-4 text-red-600 hover:text-red-800"
              >
                🗑️
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

