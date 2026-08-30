/** @type {import('next').NextConfig} */
export default {
  // The backend speaks UTC on localhost:8080. Proxying keeps the browser
  // same-origin so there is no CORS config to get wrong on stage.
  async rewrites() {
    return [{ source: '/api/:path*', destination: 'http://localhost:8080/api/:path*' }];
  },
};
