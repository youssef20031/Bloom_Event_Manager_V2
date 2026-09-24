import React, { useEffect, useState } from 'react';
import './ChatHistorySidebar.css';
import { apiFetch } from "../services/apiFetch";

interface ChatSession {
  sessionId: string;
  createdAt: string;
  messages: any[];
}

interface ChatHistorySidebarProps {
  currentSessionId: string | null;
  onSelectSession: (sessionId: string) => void;
  onNewChat: () => void;
}

export default function ChatHistorySidebar({ currentSessionId, onSelectSession, onNewChat }: ChatHistorySidebarProps) {
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    fetchSessions();
  }, [currentSessionId]); // Reload when session changes to update list if new session created

  const fetchSessions = async () => {
    try {
      setLoading(true);
      const res = await apiFetch('/api/v1/rag/chat/sessions');
      const data = await res.json();
      if (data.status === 'success') {
        setSessions(data.sessions);
      }
    } catch (err) {
      console.error("Failed to load sessions:", err);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="chat-sidebar">
      <div className="sidebar-header">
        <h3>History</h3>
        <button onClick={onNewChat} className="new-chat-btn">
          + New Chat
        </button>
      </div>
      
      <div className="session-list">
        {loading && <div className="loading">Loading...</div>}
        
        {!loading && sessions.length === 0 && (
          <div className="empty-history">No past chats</div>
        )}

        {sessions.map((session) => (
          <div 
            key={session.sessionId} 
            className={`session-item ${session.sessionId === currentSessionId ? 'active' : ''}`}
            onClick={() => onSelectSession(session.sessionId)}
          >
            <div className="session-date">
              {new Date(session.createdAt).toLocaleDateString()} {new Date(session.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            </div>
            {/* Show first user message as preview if available, else 'New Chat' */}
            <div className="session-preview">
                 {session.messages?.[0]?.content ? session.messages[0].content.substring(0, 30) + '...' : 'New Chat'}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
