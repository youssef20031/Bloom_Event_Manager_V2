import { RagChunk } from "../types";

type Props = {
  chunks: RagChunk[];
};

const ChunkList = ({ chunks }: Props) => {
  if (!chunks.length) return null;

  const getRelevanceLabel = (score: number) => {
    if (score >= 0.9) return { label: "Highly Relevant", color: "#10b981", emoji: "🎯" };
    if (score >= 0.8) return { label: "Very Relevant", color: "#3b82f6", emoji: "✨" };
    if (score >= 0.7) return { label: "Relevant", color: "#8b5cf6", emoji: "👍" };
    return { label: "Somewhat Relevant", color: "#6b7280", emoji: "📄" };
  };

  return (
    <section className="chunk-results">
      <h2>🔍 Search Results ({chunks.length} found)</h2>
      <ul className="chunk-list">
        {chunks.map((chunk, index) => {
          const relevance = getRelevanceLabel(chunk.score || 0);
          return (
            <li key={chunk.id} className="chunk-item setting-card">
              <div className="chunk-header">
                <span className="chunk-rank">#{index + 1}</span>
                <div className="chunk-meta">
                  <strong className="chunk-source">{chunk.source}</strong>
                  <span className="chunk-page">Page {chunk.page ?? "?"}</span>
                </div>
                <div className="relevance-badge status-badge" style={{ background: relevance.color, color: 'var(--color-paper-light)' }}>
                  <span>{relevance.emoji}</span>
                  <span>{relevance.label}</span>
                  <span className="score-value">{(chunk.score || 0).toFixed(3)}</span>
                </div>
              </div>
              <p className="chunk-text">{chunk.text}</p>
            </li>
          );
        })}
      </ul>
    </section>
  );
};

export default ChunkList;

