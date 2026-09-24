import { useState } from "react";

import { RagChunk } from "../types";
import { apiFetch } from "../services/apiFetch";
import { errorMessage, readJsonBody } from "../services/problem";

type Props = {
  onRetrieved: (chunks: RagChunk[]) => void;
};

const QueryForm = ({ onRetrieved }: Props) => {
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [useAI, setUseAI] = useState(true);
  const [searchMetadata, setSearchMetadata] = useState<any>(null);

  const handleSearch = async () => {
    if (!query.trim()) return;

    setLoading(true);
    setError(null);
    setSearchMetadata(null);

    try {
      // retrieval-service embeds the query itself (ai-service owns the Gemini
      // credentials), so the client sends the text and nothing else.
      const response = await apiFetch("/api/v1/rag/retrieve", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ query, useAI }),
      });
      const data = (await readJsonBody(response)) as { chunks?: RagChunk[]; metadata?: unknown } | null;
      if (!response.ok) throw new Error(errorMessage(data, "Retrieval failed"));
      onRetrieved(data?.chunks ?? []);
      setSearchMetadata(data?.metadata ?? null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="query-form-section">
      <h2>🔍 AI-Powered Search</h2>

      <div className="search-options">
        <label className="checkbox-label ai-toggle">
          <input
            type="checkbox"
            checked={useAI}
            onChange={(e) => setUseAI(e.target.checked)}
          />
          <span className="checkbox-custom"></span>
          <span>✨ Use AI Enhancement (query expansion + smart ranking)</span>
        </label>
      </div>

      <div className="form-group">
        <textarea
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Ask something about your PDFs (e.g., 'What is RAID?', 'Explain cloud storage')"
          rows={3}
        />
      </div>
      <button className="btn btn-primary" onClick={handleSearch} disabled={loading || !query.trim()}>
        {loading ? "🤖 AI Searching..." : "Search"}
      </button>

      {error && <p className="error">{error}</p>}

      {searchMetadata && searchMetadata.aiEnhanced && searchMetadata.enhancedQuery && (
        <div className="search-metadata">
          <details>
            <summary>🤖 AI Enhanced Search</summary>
            <div className="metadata-content">
              <p><strong>Your query:</strong> {searchMetadata.originalQuery}</p>
              <p><strong>AI enhanced to:</strong> {searchMetadata.enhancedQuery}</p>
              <p><strong>Results:</strong> Retrieved {searchMetadata.totalRetrieved}, showing top {searchMetadata.returned}</p>
            </div>
          </details>
        </div>
      )}
    </section>
  );
};

export default QueryForm;
