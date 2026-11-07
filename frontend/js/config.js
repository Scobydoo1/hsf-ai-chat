// Where is the back-end (Spring Boot)?
// - Opened on your PC (localhost / 127.0.0.1)  -> the local back-end on port 8080
// - Opened on Vercel                           -> your Render URL: CHANGE THIS after you deploy the back-end
window.APP_CONFIG = {
    API_BASE_URL: (location.hostname === 'localhost' || location.hostname === '127.0.0.1' || location.protocol === 'file:')
        ? 'http://localhost:8080'
        : 'https://YOUR-BACKEND.onrender.com'
};
