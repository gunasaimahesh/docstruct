import type { Metadata } from 'next';
import Link from 'next/link';
import './globals.css';
import ToastContainer from '@/components/Toast';

export const metadata: Metadata = {
  title: 'DocStruct — Turn Messy Documents into Structured Data',
  description:
    'Upload messy documents (PDFs, images, CSVs) and get clean, structured, queryable data instantly. Powered by AI schema inference.',
  keywords: ['document parser', 'data extraction', 'PDF to data', 'AI', 'structured data'],
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>
        <Header />
        <main>{children}</main>
        <ToastContainer />
      </body>
    </html>
  );
}

function Header() {
  return (
    <header className="header">
      <Link href="/" className="header-logo">
        <div className="header-logo-icon">⚡</div>
        DocStruct
      </Link>
      <nav className="header-nav">
        <Link href="/" className="header-nav-link">
          Upload
        </Link>
        <Link href="/collections" className="header-nav-link">
          Collections
        </Link>
      </nav>
    </header>
  );
}
