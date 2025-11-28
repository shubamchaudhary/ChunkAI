import { useState, useEffect } from 'react';
import { documentAPI } from '../services/api';

export default function FileViewer({ file }) {
  const [fileContent, setFileContent] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (file) {
      loadFile();
    }
  }, [file]);

  const loadFile = async () => {
    if (!file) return;
    
    setLoading(true);
    setError(null);
    
    try {
      // For PDFs, we'd need a PDF viewer library
      // For now, show file info
      setFileContent({
        type: 'info',
        data: file,
      });
    } catch (error) {
      console.error('Failed to load file:', error);
      setError('Failed to load file');
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return <div className="p-8 text-center">Loading file...</div>;
  }

  if (error) {
    return <div className="p-8 text-center text-red-600">{error}</div>;
  }

  if (!file) {
    return <div className="p-8 text-center text-gray-500">No file selected</div>;
  }

  return (
    <div className="p-6 h-full overflow-y-auto">
      <div className="mb-4 border-b pb-4">
        <h2 className="text-xl font-bold">{file.fileName}</h2>
        <div className="text-sm text-gray-500 mt-2">
          <p>Type: {file.fileType.toUpperCase()}</p>
          <p>Size: {(file.fileSizeBytes / 1024 / 1024).toFixed(2)} MB</p>
          {file.totalPages && <p>Pages: {file.totalPages}</p>}
          {file.totalChunks && <p>Chunks: {file.totalChunks}</p>}
          <p>Status: {file.processingStatus}</p>
        </div>
      </div>

      {file.processingStatus === 'COMPLETED' ? (
        <div className="bg-gray-50 p-4 rounded">
          <p className="text-gray-700">
            File processed successfully. You can now query this document using the query interface.
          </p>
          {file.fileType === 'pdf' && (
            <p className="text-sm text-gray-500 mt-2">
              Note: PDF viewer integration would go here. For now, the file is ready for querying.
            </p>
          )}
        </div>
      ) : file.processingStatus === 'PROCESSING' ? (
        <div className="bg-blue-50 p-4 rounded">
          <p className="text-blue-700">File is being processed. Please wait...</p>
        </div>
      ) : file.processingStatus === 'FAILED' ? (
        <div className="bg-red-50 p-4 rounded">
          <p className="text-red-700">Processing failed: {file.errorMessage || 'Unknown error'}</p>
        </div>
      ) : (
        <div className="bg-yellow-50 p-4 rounded">
          <p className="text-yellow-700">File is queued for processing...</p>
        </div>
      )}
    </div>
  );
}

