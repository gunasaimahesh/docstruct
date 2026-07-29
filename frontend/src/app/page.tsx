'use client';

import { useState, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import UploadZone from '@/components/UploadZone';
import { showToast } from '@/components/Toast';

export default function HomePage() {
  const router = useRouter();
  const [isUploading, setIsUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<{ step: string; percent: number } | null>(null);

  const handleUpload = useCallback(
    async (file: File) => {
      setIsUploading(true);
      setUploadProgress({ step: 'Reading document...', percent: 10 });

      try {
        const formData = new FormData();
        formData.append('file', file);

        setUploadProgress({ step: 'Parsing document...', percent: 30 });

        // Brief delay so users see the progress stages
        await new Promise(r => setTimeout(r, 300));
        setUploadProgress({ step: 'Inferring schema with AI...', percent: 50 });

        const response = await fetch('/api/collections', {
          method: 'POST',
          body: formData,
        });

        setUploadProgress({ step: 'Structuring data...', percent: 80 });

        const result = await response.json();

        if (!response.ok || !result.success) {
          throw new Error(result.error || 'Upload failed');
        }

        setUploadProgress({ step: 'Done!', percent: 100 });

        showToast('success', `Extracted ${result.extraction.rowCount} rows from "${file.name}"`);

        // Navigate to the collection
        setTimeout(() => {
          router.push(`/collections/${result.collection.id}`);
        }, 500);
      } catch (error) {
        const message = error instanceof Error ? error.message : 'Upload failed';
        showToast('error', message);
        setIsUploading(false);
        setUploadProgress(null);
      }
    },
    [router]
  );

  return (
    <div className="page-container home-page">
      <section className="home-intro">
        <p className="eyebrow">Document workspace</p>
        <h1 className="page-title">Create a structured document collection</h1>
        <p className="page-subtitle">Upload a document to extract its data, review the knowledge model, and query the result.</p>
      </section>

      <section className="card home-upload-card">
        <div className="card-heading">
          <div>
            <h2>Upload a document</h2>
            <p>PDF, image, CSV, or text files are supported.</p>
          </div>
        </div>
        <UploadZone
          onUpload={handleUpload}
          isUploading={isUploading}
          uploadProgress={uploadProgress}
        />
      </section>

      <section className="home-steps-section">
        <h2>Workflow</h2>
        <div className="home-steps">
          <StepCard
            number="1"
            icon="📄"
            title="Upload"
            description="Drop your messy document — invoices, receipts, bank statements, resumes, anything"
          />
          <StepCard
            number="2"
            icon="🧠"
            title="AI Structures"
            description="Our AI reads the document, infers a schema, and extracts every data point with confidence scores"
          />
          <StepCard
            number="3"
            icon="🔍"
            title="Query & Export"
            description="Search your data in plain English, edit any mistakes inline, and export as CSV or JSON"
          />
        </div>
      </section>
    </div>
  );
}

function StepCard({
  number,
  icon,
  title,
  description,
}: {
  number: string;
  icon: string;
  title: string;
  description: string;
}) {
  return (
    <div className="card step-card">
      <div className="step-icon">
        {icon}
      </div>
      <div className="step-number">
        STEP {number}
      </div>
      <div className="step-title">
        {title}
      </div>
      <div className="step-description">
        {description}
      </div>
    </div>
  );
}
