import type { NextConfig } from "next";

// All /api/* requests are proxied to the Spring Boot backend,
// so client code can keep using same-origin fetch calls.
const API_URL = process.env.API_URL || "http://localhost:8080";

const nextConfig: NextConfig = {
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${API_URL}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
