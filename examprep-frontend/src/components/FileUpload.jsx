import { useState } from 'react';
import { documentAPI } from '../services/api';

export default function FileUpload({ chatId, onUpload }) {
  const [files, setFiles] = useState([]);
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState({});

  const handleFileSelect = (e) => {
    const selectedFiles = Array.from(e.target.files);
    if (selectedFiles.length + files.length > 100) {
      alert('Maximum 100 files allowed');
      return;
    }
    setFiles([...files, ...selectedFiles]);
  };

  const handleUpload = async () => {
    if (files.length === 0) return;

    setUploading(true);
    try {
      const response = await documentAPI.uploadBulk(files, chatId);
      const result = response.data;
      
      // Show results
      if (result.duplicates && result.duplicates.length > 0) {
        alert(`${result.successfulUploads} files uploaded. ${result.duplicateCount} duplicates skipped.`);
      } else {
        alert(`${result.successfulUploads} files uploaded successfully!`);
      }
      
      setFiles([]);
      onUpload();
    } catch (error) {
      console.error('Upload failed:', error);
      alert('Upload failed: ' + (error.response?.data?.message || error.message));
    } finally {
      setUploading(false);
      setProgress({});
    }
  };

  const removeFile = (index) => {
    setFiles(files.filter((_, i) => i !== index));
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center space-x-4">
        <label className="cursor-pointer">
          <span className="px-4 py-2 bg-indigo-600 text-white rounded-md hover:bg-indigo-700 inline-block">
            Select Files (up to 100)
          </span>
          <input
            type="file"
            multiple
            className="hidden"
            onChange={handleFileSelect}
            accept=".pdf,.ppt,.pptx,.jpg,.jpeg,.png,.txt"
          />
        </label>
        {files.length > 0 && (
          <button
            onClick={handleUpload}
            disabled={uploading}
            className="px-4 py-2 bg-green-600 text-white rounded-md hover:bg-green-700 disabled:opacity-50"
          >
            {uploading ? 'Uploading...' : `Upload ${files.length} file(s)`}
          </button>
        )}
      </div>

      {files.length > 0 && (
        <div className="max-h-32 overflow-y-auto space-y-1">
          {files.map((file, index) => (
            <div key={index} className="flex items-center justify-between text-sm bg-gray-50 p-2 rounded">
              <span className="truncate flex-1">{file.name}</span>
              <span className="text-gray-500 ml-2">
                {(file.size / 1024 / 1024).toFixed(2)} MB
              </span>
              <button
                onClick={() => removeFile(index)}
                className="ml-2 text-red-600 hover:text-red-800"
              >
                ×
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

