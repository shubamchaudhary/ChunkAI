import { useState, useRef, useCallback } from 'react';
import { documentAPI } from '../services/api';

/**
 * Enhanced File Upload Component for Bulk Uploads (100s of files)
 * Features:
 * - Drag and drop support
 * - Progress tracking for each file
 * - Batch upload with chunked requests
 * - File type validation
 * - Duplicate detection display
 * - Cancel upload capability
 */
export default function FileUpload({ chatId, onUpload }) {
  const [files, setFiles] = useState([]);
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [uploadedCount, setUploadedCount] = useState(0);
  const [isDragging, setIsDragging] = useState(false);
  const [uploadResult, setUploadResult] = useState(null);
  const fileInputRef = useRef(null);
  const abortControllerRef = useRef(null);

  // Supported file types
  const SUPPORTED_TYPES = ['.pdf', '.ppt', '.pptx', '.jpg', '.jpeg', '.png', '.txt', '.doc', '.docx'];
  const MAX_FILES = 500; // Increased limit for bulk uploads
  const MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB per file

  const validateFile = (file) => {
    const ext = '.' + file.name.split('.').pop().toLowerCase();
    if (!SUPPORTED_TYPES.includes(ext)) {
      return { valid: false, error: `Unsupported file type: ${ext}` };
    }
    if (file.size > MAX_FILE_SIZE) {
      return { valid: false, error: `File too large (max 100MB): ${file.name}` };
    }
    return { valid: true };
  };

  const handleFileSelect = useCallback((selectedFiles) => {
    const fileArray = Array.from(selectedFiles);

    // Check total count
    if (files.length + fileArray.length > MAX_FILES) {
      alert(`Maximum ${MAX_FILES} files allowed. You can add ${MAX_FILES - files.length} more files.`);
      return;
    }

    // Validate and filter files
    const validFiles = [];
    const errors = [];

    fileArray.forEach((file) => {
      const validation = validateFile(file);
      if (validation.valid) {
        // Check for duplicates in current selection
        const isDuplicate = files.some(f => f.name === file.name && f.size === file.size);
        if (!isDuplicate) {
          validFiles.push(file);
        } else {
          errors.push(`Duplicate skipped: ${file.name}`);
        }
      } else {
        errors.push(validation.error);
      }
    });

    if (errors.length > 0) {
      console.warn('File validation errors:', errors);
    }

    setFiles(prev => [...prev, ...validFiles]);
    setUploadResult(null);
  }, [files]);

  const handleDrop = useCallback((e) => {
    e.preventDefault();
    setIsDragging(false);
    handleFileSelect(e.dataTransfer.files);
  }, [handleFileSelect]);

  const handleDragOver = useCallback((e) => {
    e.preventDefault();
    setIsDragging(true);
  }, []);

  const handleDragLeave = useCallback((e) => {
    e.preventDefault();
    setIsDragging(false);
  }, []);

  const handleInputChange = (e) => {
    handleFileSelect(e.target.files);
    e.target.value = ''; // Reset input
  };

  const removeFile = (index) => {
    setFiles(files.filter((_, i) => i !== index));
  };

  const clearAll = () => {
    setFiles([]);
    setUploadResult(null);
    setUploadProgress(0);
    setUploadedCount(0);
  };

  const cancelUpload = () => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      setUploading(false);
      setUploadProgress(0);
    }
  };

  const handleUpload = async () => {
    if (files.length === 0) return;

    setUploading(true);
    setUploadProgress(0);
    setUploadedCount(0);
    setUploadResult(null);
    abortControllerRef.current = new AbortController();

    try {
      // Upload in batches of 20 files for better reliability
      const BATCH_SIZE = 20;
      const batches = [];
      for (let i = 0; i < files.length; i += BATCH_SIZE) {
        batches.push(files.slice(i, i + BATCH_SIZE));
      }

      let totalUploaded = 0;
      let totalDuplicates = 0;
      let totalErrors = [];
      let allUploads = [];

      for (let i = 0; i < batches.length; i++) {
        const batch = batches[i];

        try {
          const response = await documentAPI.uploadBulk(batch, chatId, {
            signal: abortControllerRef.current.signal
          });

          const result = response.data;
          totalUploaded += result.successfulUploads || 0;
          totalDuplicates += result.duplicateCount || 0;
          if (result.errors) {
            totalErrors = [...totalErrors, ...result.errors];
          }
          if (result.uploads) {
            allUploads = [...allUploads, ...result.uploads];
          }

          setUploadedCount(totalUploaded);
          setUploadProgress(Math.round(((i + 1) / batches.length) * 100));

        } catch (batchError) {
          if (batchError.name === 'AbortError') {
            throw batchError; // Rethrow abort
          }
          console.error('Batch upload error:', batchError);
          totalErrors.push(`Batch ${i + 1} failed: ${batchError.message}`);
        }
      }

      // Set final result
      setUploadResult({
        success: totalUploaded > 0,
        uploaded: totalUploaded,
        duplicates: totalDuplicates,
        errors: totalErrors,
        total: files.length
      });

      if (totalUploaded > 0) {
        setFiles([]);
        onUpload();
      }

    } catch (error) {
      if (error.name === 'AbortError') {
        setUploadResult({
          success: false,
          message: 'Upload cancelled'
        });
      } else {
        console.error('Upload failed:', error);
        setUploadResult({
          success: false,
          message: error.response?.data?.message || error.message || 'Upload failed'
        });
      }
    } finally {
      setUploading(false);
    }
  };

  const formatFileSize = (bytes) => {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
  };

  const getTotalSize = () => {
    return files.reduce((acc, file) => acc + file.size, 0);
  };

  return (
    <div className="space-y-4">
      {/* Drop Zone */}
      <div
        onDrop={handleDrop}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        className={`border-2 border-dashed rounded-xl p-8 text-center transition-all cursor-pointer ${
          isDragging
            ? 'border-indigo-500 bg-indigo-50'
            : 'border-gray-300 hover:border-indigo-400 hover:bg-gray-50'
        }`}
        onClick={() => fileInputRef.current?.click()}
      >
        <input
          ref={fileInputRef}
          type="file"
          multiple
          className="hidden"
          onChange={handleInputChange}
          accept={SUPPORTED_TYPES.join(',')}
        />

        <div className="space-y-3">
          <div className="mx-auto w-12 h-12 bg-indigo-100 rounded-full flex items-center justify-center">
            <svg className="w-6 h-6 text-indigo-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
            </svg>
          </div>

          <div>
            <p className="text-lg font-medium text-gray-700">
              {isDragging ? 'Drop files here' : 'Drag & drop files here'}
            </p>
            <p className="text-sm text-gray-500 mt-1">
              or <span className="text-indigo-600 font-medium">click to browse</span>
            </p>
          </div>

          <p className="text-xs text-gray-400">
            Supports: PDF, PPT, PPTX, DOC, DOCX, TXT, JPG, PNG (up to {MAX_FILES} files, 100MB each)
          </p>
        </div>
      </div>

      {/* File List */}
      {files.length > 0 && (
        <div className="bg-gray-50 rounded-xl p-4">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center space-x-2">
              <span className="font-medium text-gray-700">
                {files.length} file{files.length !== 1 ? 's' : ''} selected
              </span>
              <span className="text-sm text-gray-500">
                ({formatFileSize(getTotalSize())})
              </span>
            </div>
            <button
              onClick={clearAll}
              className="text-sm text-red-600 hover:text-red-800"
              disabled={uploading}
            >
              Clear all
            </button>
          </div>

          <div className="max-h-48 overflow-y-auto space-y-2 pr-2">
            {files.map((file, index) => (
              <div
                key={`${file.name}-${index}`}
                className="flex items-center justify-between bg-white p-2 rounded-lg shadow-sm"
              >
                <div className="flex items-center min-w-0 flex-1">
                  <div className="w-8 h-8 bg-gray-100 rounded flex items-center justify-center flex-shrink-0">
                    <span className="text-xs font-medium text-gray-500 uppercase">
                      {file.name.split('.').pop()}
                    </span>
                  </div>
                  <span className="ml-2 text-sm text-gray-700 truncate">
                    {file.name}
                  </span>
                </div>
                <div className="flex items-center ml-2 flex-shrink-0">
                  <span className="text-xs text-gray-500 mr-2">
                    {formatFileSize(file.size)}
                  </span>
                  {!uploading && (
                    <button
                      onClick={() => removeFile(index)}
                      className="text-gray-400 hover:text-red-600"
                    >
                      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                      </svg>
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Upload Progress */}
      {uploading && (
        <div className="bg-indigo-50 rounded-xl p-4">
          <div className="flex items-center justify-between mb-2">
            <span className="text-sm font-medium text-indigo-700">
              Uploading... {uploadedCount} of {files.length} files
            </span>
            <button
              onClick={cancelUpload}
              className="text-sm text-red-600 hover:text-red-800"
            >
              Cancel
            </button>
          </div>
          <div className="h-2 bg-indigo-200 rounded-full overflow-hidden">
            <div
              className="h-full bg-indigo-600 transition-all duration-300"
              style={{ width: `${uploadProgress}%` }}
            ></div>
          </div>
          <p className="text-xs text-indigo-600 mt-1 text-center">
            {uploadProgress}% complete
          </p>
        </div>
      )}

      {/* Upload Result */}
      {uploadResult && (
        <div className={`rounded-xl p-4 ${
          uploadResult.success ? 'bg-green-50' : 'bg-red-50'
        }`}>
          <div className="flex items-start">
            {uploadResult.success ? (
              <svg className="w-5 h-5 text-green-600 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            ) : (
              <svg className="w-5 h-5 text-red-600 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            )}
            <div className="ml-3 flex-1">
              {uploadResult.success ? (
                <>
                  <p className="text-sm font-medium text-green-800">
                    Successfully uploaded {uploadResult.uploaded} file{uploadResult.uploaded !== 1 ? 's' : ''}
                  </p>
                  {uploadResult.duplicates > 0 && (
                    <p className="text-xs text-green-600 mt-1">
                      {uploadResult.duplicates} duplicate{uploadResult.duplicates !== 1 ? 's' : ''} skipped
                    </p>
                  )}
                </>
              ) : (
                <p className="text-sm font-medium text-red-800">
                  {uploadResult.message || 'Upload failed'}
                </p>
              )}
              {uploadResult.errors && uploadResult.errors.length > 0 && (
                <details className="mt-2">
                  <summary className="text-xs text-gray-600 cursor-pointer">
                    {uploadResult.errors.length} error{uploadResult.errors.length !== 1 ? 's' : ''}
                  </summary>
                  <ul className="text-xs text-gray-500 mt-1 ml-4 list-disc">
                    {uploadResult.errors.slice(0, 5).map((error, i) => (
                      <li key={i}>{error}</li>
                    ))}
                    {uploadResult.errors.length > 5 && (
                      <li>...and {uploadResult.errors.length - 5} more</li>
                    )}
                  </ul>
                </details>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Upload Button */}
      {files.length > 0 && !uploading && (
        <button
          onClick={handleUpload}
          className="w-full py-3 px-4 bg-indigo-600 hover:bg-indigo-700 text-white font-medium rounded-lg transition-colors flex items-center justify-center"
        >
          <svg className="w-5 h-5 mr-2" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
          </svg>
          Upload {files.length} file{files.length !== 1 ? 's' : ''}
        </button>
      )}
    </div>
  );
}
