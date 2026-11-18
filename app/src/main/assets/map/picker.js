const map = L.map('map', {worldCopyJump: true}).setView([20, 0], 2);
const statusView = document.getElementById('status');
let marker;
let tilesAvailable = false;
const tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '© OpenStreetMap contributors (ODbL)'
}).addTo(map);
function updateStatus() {
    if (!tilesAvailable) return;
    const point = window.startPoint;
    statusView.textContent = point ? `${point[0].toFixed(6)}, ${point[1].toFixed(6)}`
        : 'Zoom in and tap the map to place a pin.';
}
const loaded = new WeakSet();
tiles.on('tileload', ({tile}) => { loaded.add(tile); tilesAvailable = true; updateStatus(); });
tiles.on('tileerror', () => {
    if (!tilesAvailable) statusView.textContent = 'Map tiles unavailable. Check your internet connection.';
});
function select(point) {
    const wrapped = point.wrap();
    marker = marker ? marker.setLatLng(wrapped) : L.marker(wrapped).addTo(map);
    window.startPoint = [wrapped.lat, wrapped.lng];
    updateStatus();
}
map.on('click', ({latlng, originalEvent}) => {
    if (document.elementsFromPoint(originalEvent.clientX, originalEvent.clientY).some(el => loaded.has(el))) {
        select(latlng);
    }
});
window.centerMap = function (lat, lon) {
    const point = L.latLng(lat, lon);
    map.setView(point, 15);
    select(point);
};
