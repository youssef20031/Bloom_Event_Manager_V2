import { useState } from "react";

import { apiFetch } from "../services/apiFetch";
import { errorMessage, readJsonBody } from "../services/problem";

/** Shape of GET /api/v1/rag/ingest/{jobId} (ingestion-service's IngestJob). */
interface IngestJob {
  jobId: string;
  status: "RUNNING" | "COMPLETED" | "FAILED";
  results?: unknown[];
  errors?: string[];
}

const POLL_INTERVAL_MS = 1000;
const POLL_MAX_ATTEMPTS = 120;

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const PdfUploader = () => {
  const [status, setStatus] = useState<string | null>(null);

  const handleIngest = async () => {
    setStatus("Ingesting...");
    try {
      // Phase 3a made ingestion asynchronous: the POST returns 202 { jobId }
      // immediately, and the job is polled until it leaves RUNNING.
      const response = await apiFetch("/api/v1/rag/ingest", { method: "POST" });
      const data = await readJsonBody(response);
      if (!response.ok) {
        setStatus(errorMessage(data, "Failed"));
        return;
      }

      let job = data as IngestJob;
      for (let attempt = 0; job.status === "RUNNING" && attempt < POLL_MAX_ATTEMPTS; attempt++) {
        await sleep(POLL_INTERVAL_MS);
        const poll = await apiFetch(`/api/v1/rag/ingest/${job.jobId}`);
        if (!poll.ok) {
          setStatus("Failed to read ingestion status");
          return;
        }
        job = (await poll.json()) as IngestJob;
      }

      if (job.status === "RUNNING") {
        setStatus("Still ingesting — check back shortly");
      } else if (job.status === "COMPLETED") {
        setStatus(`Processed ${job.results?.length ?? 0} file(s)`);
      } else {
        setStatus(job.errors?.[0] || "Failed");
      }
    } catch (error) {
      setStatus(String(error));
    }
  };

  return (
    <section className="pdf-uploader-section">
      <h2>Ingestion</h2>
      <button className="btn btn-primary" onClick={handleIngest}>Run ingestion</button>
      {status && <p className="status-message">{status}</p>}
    </section>
  );
};

export default PdfUploader;
