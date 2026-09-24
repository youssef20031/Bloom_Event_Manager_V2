import { useState, useRef, useEffect } from "react";
import "./ChatInterface.css";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { formatXmlTags } from "../utils/formatXmlTags";
import { apiFetch } from "../services/apiFetch";
import { errorMessage, readJsonBody } from "../services/problem";

const PROMPT_SUGGESTIONS = [
  {
    category: "Activities & Focus",
    prompts: [
      "Suggest activities related to life experiences.",
      "What are some art and culture-focused event ideas?",
      "Give me history-themed activities for a team offsite.",
      "List some fun facts and trivia activities."
    ]
  },
  {
    category: "Event Planning",
    prompts: [
      "Generate a detailed event timeline for a full-day conference.",
      "Create a step-by-step schedule for a product launch."
    ]
  },
  {
    category: "Language Tone",
    prompts: [
      "Draft an invite in a friendly and welcoming tone.",
      "Write an event description in a scientific and professional tone.",
      "Create a brief that is easy to understand for beginners."
    ]
  },
  {
    category: "Event Vibes",
    prompts: [
      "Suggest ideas for an artistic and creative event vibe.",
      "How can I create a historical and vintage atmosphere?",
      "Design a futuristic and technology-driven event space."
    ]
  },
  {
    category: "Complexity",
    prompts: [
      "Suggest some low-complexity activities for a quick warmup.",
      "Plan a highly complex, multi-stage puzzle activity.",
      "Give me activities with moderate complexity requiring team coordination."
    ]
  }
];

interface Message {
  role: "user" | "model";
  content: string;
  sources?: string[];
  thoughts?: string;
}

function SuggestionsList({ onSelect }: { onSelect: (prompt: string) => void }) {
  return (
    <div className="prompt-suggestions-container">
      {PROMPT_SUGGESTIONS.map((group, idx) => (
        <div key={idx} className="suggestion-group">
          <h4>{group.category}</h4>
          <div className="suggestion-chips">
            {group.prompts.map((prompt, pIdx) => (
              <button 
                key={pIdx} 
                className="suggestion-chip"
                onClick={() => onSelect(prompt)}
              >
                {prompt}
              </button>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

interface ChatResponse {
  status: string;
  answer?: string;
  sessionId?: string;
  sources?: string[];
  thoughts?: string;
  message?: string; // Error message
}

import ChatHistorySidebar from "./ChatHistorySidebar";

function ThinkingBlock({ thoughts }: { thoughts: string }) {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <div className="message-thoughts">
      <button 
        className="thinking-toggle-btn"
        onClick={() => setIsOpen(!isOpen)}
      >
        {isOpen ? "▼ Hide Thinking Process" : "▶ Show Thinking Process"}
      </button>
      {isOpen && (
        <div className="thoughts-content">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{formatXmlTags(thoughts)}</ReactMarkdown>
        </div>
      )}
    </div>
  );
}

export default function ChatInterface() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [showSuggestions, setShowSuggestions] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  };

  useEffect(scrollToBottom, [messages]);

  const handleNewChat = () => {
    setSessionId(null);
    setMessages([]);
  };

  const handleSelectSession = async (id: string) => {
    if (id === sessionId) return;

    setLoading(true);
    setSessionId(id);
    try {
      const res = await apiFetch(`/api/v1/rag/chat/sessions/${id}`);
      const data = await res.json();
      if (data.status === "success" && data.session) {
        setMessages(data.session.messages.map((m: any) => ({
          role: m.role,
          content: m.content,
          sources: m.sources,
          thoughts: m.thoughts
        })));
      }
    } catch (err) {
      console.error("Error loading session:", err);
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!input.trim() || loading) return;

    const userMessage = input.trim();
    setInput("");
    setShowSuggestions(false);
    setLoading(true);

    // Optimistically add user message
    setMessages((prev) => [...prev, { role: "user", content: userMessage }]);

    try {
      const response = await apiFetch("/api/v1/rag/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: userMessage, sessionId }),
      });

      const data = (await readJsonBody(response)) as ChatResponse | null;

      if (data?.status === "success" && data.answer && data.sessionId) {
        setSessionId(data.sessionId);
        setMessages((prev) => [
          ...prev,
          { role: "model", content: data.answer!, sources: data.sources || [], thoughts: data.thoughts },
        ]);
      } else {
        console.error("Chat error:", data);
        setMessages((prev) => [
          ...prev,
          { role: "model", content: `⚠️ **System Error**: ${errorMessage(data, "An unexpected error occurred.")}` },
        ]);
      }
    } catch (error) {
      console.error("Network error:", error);
    } finally {
      setLoading(false);
    }
  };

  const [uploading, setUploading] = useState(false);

  const handleBackup = async () => {
    if (!sessionId) return;
    setUploading(true);
    try {
      const res = await apiFetch(`/api/v1/rag/chat/sessions/${sessionId}/backup`, { method: "POST" });
      const data = (await readJsonBody(res)) as { status?: string; drive?: { folderId?: string } } | null;
      if (data?.status === "success") {
        alert(`Backup saved to Drive!\nFolder: ${data.drive?.folderId}`);
      } else {
        alert("Backup failed: " + errorMessage(data, "An unexpected error occurred."));
      }
    } catch (err) {
      console.error("Backup error:", err);
      alert("Backup error");
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="chat-layout">
      <ChatHistorySidebar
        currentSessionId={sessionId}
        onSelectSession={handleSelectSession}
        onNewChat={handleNewChat}
      />

      <div className="chat-container">{sessionId && (
        <div className="chat-header-actions">
          <button
            className="action-button"
            onClick={handleBackup}
            disabled={uploading}
          >
            {uploading ? "💾 Saving..." : "💾 Save to Drive (PDF)"}
          </button>
        </div>
      )}

        <div className="chat-history">
          {messages.length === 0 && (
            <div className="empty-state">
              <h3>✨ Event Advisor AI</h3>
              <p>I can help you plan events, analyze culture, and innovate based on your briefs.</p>
              
              <SuggestionsList onSelect={(prompt) => {
                setInput(prompt);
                messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
              }} />
            </div>
          )}
          {messages.map((msg, index) => (
            <div key={index} className={`chat-message ${msg.role}`}>
              <div className="message-bubble">
                <div className="message-content">
                  {msg.thoughts && (
                    <ThinkingBlock thoughts={msg.thoughts} />
                  )}
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{formatXmlTags(msg.content)}</ReactMarkdown>
                </div>
                {msg.sources && msg.sources.length > 0 && (
                  <div className="message-sources">
                    <strong>Sources:</strong>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', marginTop: '4px' }}>
                      {msg.sources.map((source, idx) => (
                        <span key={idx} style={{ background: 'var(--color-paper)', padding: '4px 8px', borderRadius: '0', fontSize: '0.85em', border: 'var(--border-width) solid var(--color-ink)' }}>
                          [{idx + 1}] {source}
                        </span>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </div>
          ))}
          {loading && (
            <div className="chat-message model">
              <div className="message-bubble thinking">Thinking...</div>
            </div>
          )}
          <div ref={messagesEndRef} />
        </div>

        <form onSubmit={handleSubmit} className="chat-input-form">
          {showSuggestions && messages.length > 0 && (
            <div className="suggestions-tray">
              <div className="suggestions-tray-header">
                <h3>Prompt Suggestions</h3>
                <button type="button" className="close-suggestions-btn" onClick={() => setShowSuggestions(false)}>✕</button>
              </div>
              <SuggestionsList onSelect={(prompt) => {
                setInput(prompt);
                setShowSuggestions(false);
              }} />
            </div>
          )}

          {messages.length > 0 && (
            <button 
              type="button" 
              className="toggle-suggestions-btn" 
              onClick={() => setShowSuggestions(!showSuggestions)}
              title="Show prompt suggestions"
            >
              💡
            </button>
          )}
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="Ask a question or request an event plan..."
            disabled={loading}
          />
          <button type="submit" disabled={loading || !input.trim()}>
            ➔
          </button>
        </form>
      </div>
    </div>
  );
}
