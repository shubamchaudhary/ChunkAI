import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

// Connection state for UI feedback
let connectionState = {
  isConnected: false,
  isWakingUp: false,
  lastPing: null,
  listeners: new Set(),
};

// Notify all listeners of state change
const notifyListeners = () => {
  connectionState.listeners.forEach(listener => listener({ ...connectionState }));
};

// Subscribe to connection state changes
export const subscribeToConnectionState = (listener) => {
  connectionState.listeners.add(listener);
  listener({ ...connectionState }); // Immediate callback with current state
  return () => connectionState.listeners.delete(listener);
};

const api = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000, // 60 second default timeout (for cold starts)
  withCredentials: false,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor - Add token and handle cold starts
api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }

    // Mark as waking up if this might trigger a cold start
    if (!connectionState.isConnected && !connectionState.isWakingUp) {
      connectionState.isWakingUp = true;
      notifyListeners();
    }

    console.log(`[API] ${config.method?.toUpperCase()} ${config.url}`);
    return config;
  },
  (error) => {
    console.error('[API Request Error]', error);
    return Promise.reject(error);
  }
);

// Response interceptor - Handle errors and track connection state
api.interceptors.response.use(
  (response) => {
    // Mark as connected on successful response
    if (!connectionState.isConnected || connectionState.isWakingUp) {
      connectionState.isConnected = true;
      connectionState.isWakingUp = false;
      connectionState.lastPing = Date.now();
      notifyListeners();
    }
    return response;
  },
  (error) => {
    // Handle specific error types
    if (error.code === 'ECONNABORTED' || error.message?.includes('timeout')) {
      console.error('[API Timeout]', error.config?.url);
      connectionState.isWakingUp = true;
      connectionState.isConnected = false;
      notifyListeners();

      error.userMessage = 'Server is waking up... This may take 30-60 seconds on free tier.';
    } else if (error.response) {
      console.error('[API Error]', {
        status: error.response.status,
        url: error.config?.url,
      });

      if (error.response.status >= 500) {
        error.userMessage = 'Server error. Please try again.';
      } else if (error.response.status === 401) {
        error.userMessage = 'Session expired. Please log in again.';
        localStorage.removeItem('token');
      } else {
        error.userMessage = error.response.data?.message || 'Request failed.';
      }
    } else if (error.request) {
      console.error('[API Network Error]', error.message);
      connectionState.isConnected = false;
      connectionState.isWakingUp = true;
      notifyListeners();

      error.userMessage = 'Cannot connect to server. It may be starting up...';
    }

    return Promise.reject(error);
  }
);

// Health check / ping function
export const pingServer = async () => {
  try {
    const start = Date.now();
    await axios.get(`${API_BASE_URL}/health/ping`, { timeout: 10000 });
    const latency = Date.now() - start;

    connectionState.isConnected = true;
    connectionState.isWakingUp = false;
    connectionState.lastPing = Date.now();
    notifyListeners();

    return { connected: true, latency };
  } catch (error) {
    connectionState.isConnected = false;
    notifyListeners();
    return { connected: false, error: error.message };
  }
};

// Wake up server (call on app load)
export const wakeUpServer = async () => {
  connectionState.isWakingUp = true;
  notifyListeners();

  try {
    // First try a simple ping
    const result = await pingServer();
    if (result.connected) {
      // Then call warmup endpoint
      try {
        await axios.get(`${API_BASE_URL}/health/warmup`, { timeout: 30000 });
      } catch (e) {
        // Warmup is optional, ignore errors
      }
    }
    return result;
  } catch (error) {
    return { connected: false, error: error.message };
  }
};

// Auth API
export const authAPI = {
  register: (data) => api.post('/auth/register', data),
  login: (data) => api.post('/auth/login', data),
  validate: () => api.get('/auth/validate'),
};

// Chat API
export const chatAPI = {
  create: (data) => api.post('/chats', data),
  getAll: (params) => api.get('/chats', { params }),
  getById: (id) => api.get(`/chats/${id}`),
  update: (id, data) => api.put(`/chats/${id}`, data),
  delete: (id) => api.delete(`/chats/${id}`),
};

// Document API
export const documentAPI = {
  upload: (file, chatId) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('chatId', chatId);
    return api.post('/documents/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 300000, // 5 minutes for single file
    });
  },
  uploadBulk: (files, chatId, options = {}) => {
    const formData = new FormData();
    files.forEach(file => formData.append('files', file));
    formData.append('chatId', chatId);
    return api.post('/documents/upload/bulk', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 1800000, // 30 minutes for bulk
      ...options,
    });
  },
  getAll: (params) => api.get('/documents', { params }),
  getById: (id) => api.get(`/documents/${id}`),
  delete: (id, chatId) => api.delete(`/documents/${id}`, { params: { chatId } }),
  getStatus: (id) => api.get(`/documents/${id}/status`),
};

// Query API
export const queryAPI = {
  query: (data) => api.post('/query', data, {
    timeout: 120000, // 2 minutes for RAG queries (accounts for cold starts + processing)
  }),
  getHistory: (params) => api.get('/query/history', { params }),
};

export default api;
