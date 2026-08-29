(() => {
    'use strict';

    const params = new URLSearchParams(window.location.search);
    const minimumZoom = 0.8;
    const maximumZoom = 3.0;
    const systemCountHint = Math.max(1, Number(params.get('systems')) || 1);
    const initialSystem = Math.max(0, Number(params.get('system')) || 0);
    const cursorEnabled = params.has('cursor');
    let cursorStep = Number(params.get('cursor')) || 0;
    let currentZoom = clamp(Number(params.get('zoom')) || 1.0);
    let osmd = null;
    let scrollTimer = null;

    function clamp(value) {
        return Math.min(maximumZoom, Math.max(minimumZoom, value));
    }

    function callAndroid(method, value) {
        try {
            if (!window.AndroidOsmd) return;
            if (method === 'onRendered') window.AndroidOsmd.onRendered(value);
            else if (method === 'onError') window.AndroidOsmd.onError(value);
            else if (method === 'onZoomChanged') window.AndroidOsmd.onZoomChanged(value);
            else if (method === 'onSystemChanged') window.AndroidOsmd.onSystemChanged(value);
        } catch (error) {
            console.error('Android OSMD bridge failed: ' + (error && error.message ? error.message : error));
        }
    }

    function reportError(error) {
        const message = error && error.message ? error.message : String(error || 'Unknown rendering error');
        if (window.sheetSight) {
            window.sheetSight.renderState = 'error';
            window.sheetSight.renderError = message;
        }
        document.getElementById('score').style.display = 'none';
        const errorElement = document.getElementById('error');
        errorElement.textContent = 'This score could not be engraved: ' + message;
        errorElement.style.display = 'block';
        callAndroid('onError', message);
    }

    function restoreReadingPosition() {
        if (systemCountHint <= 1 || initialSystem <= 0) return;
        const scrollRange = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
        const progress = Math.min(1, initialSystem / (systemCountHint - 1));
        window.scrollTo(0, scrollRange * progress);
    }

    function addDefaultClefs(xmlDocument) {
        const root = xmlDocument.documentElement;
        if (!root || root.localName !== 'score-partwise') return;

        // Older converters (including abc2xml) may omit metadata that OSMD 2.x
        // assumes is present. The document is still valid enough for Practice,
        // so supply conservative engraving defaults in the in-memory copy only.
        if (!root.hasAttribute('version')) root.setAttribute('version', '3.0');
        root.querySelectorAll('score-part > part-name').forEach((partName) => {
            if (!partName.textContent.trim()) partName.textContent = 'Music';
        });

        root.querySelectorAll('part').forEach((part) => {
            if (part.querySelector('clef') || !part.querySelector('note > pitch')) return;
            const firstMeasure = part.querySelector('measure');
            if (!firstMeasure) return;

            let attributes = firstMeasure.querySelector(':scope > attributes');
            if (!attributes) {
                attributes = xmlDocument.createElement('attributes');
                firstMeasure.insertBefore(attributes, firstMeasure.firstChild);
            }
            const clef = xmlDocument.createElement('clef');
            const sign = xmlDocument.createElement('sign');
            const line = xmlDocument.createElement('line');
            sign.textContent = 'G';
            line.textContent = '2';
            clef.appendChild(sign);
            clef.appendChild(line);
            attributes.appendChild(clef);
        });
    }

    function assertVisibleNotation() {
        const svg = document.querySelector('#score svg');
        if (!svg || !svg.querySelector('path, line, polyline, polygon, text, use')) {
            throw new Error('OSMD did not produce visible notation for this score.');
        }
    }

    function positionCursor(step) {
        if (!cursorEnabled || !osmd || !osmd.cursor) return;
        cursorStep = Number.isFinite(Number(step)) ? Math.trunc(Number(step)) : -1;
        osmd.cursor.hide();
        osmd.cursor.reset();
        if (cursorStep < 0) return;
        osmd.cursor.show();
        for (let index = 0; index < cursorStep; index += 1) {
            osmd.cursor.next();
        }
    }

    async function render() {
        try {
            const response = await fetch('score.musicxml', { cache: 'no-store' });
            if (!response.ok) throw new Error('MusicXML could not be loaded.');
            const musicXml = await response.text();
            const xmlDocument = new DOMParser().parseFromString(musicXml, 'application/xml');
            const parserError = xmlDocument.querySelector('parsererror');
            if (parserError) throw new Error('MusicXML is not valid XML.');
            addDefaultClefs(xmlDocument);
            osmd = new opensheetmusicdisplay.OpenSheetMusicDisplay('score', {
                backend: 'svg',
                autoResize: true,
                pageFormat: 'Endless',
                drawingParameters: 'compacttight',
                drawCredits: true,
                drawTitle: true,
                drawComposer: true,
                drawPartNames: true,
                drawPartAbbreviations: true,
                drawMeasureNumbers: true,
                drawMeasureNumbersOnlyAtSystemStart: true,
                alignRests: 2,
                autoBeam: true,
                disableCursor: !cursorEnabled,
                followCursor: cursorEnabled,
                pageBackgroundColor: '#FFFEFA'
            });
            await osmd.load(xmlDocument);
            osmd.zoom = currentZoom;
            osmd.render();
            assertVisibleNotation();
            try {
                positionCursor(cursorStep);
            } catch (cursorError) {
                // Cursor support is optional; never hide a successfully engraved score.
                console.warn('OSMD cursor could not be positioned: ' + cursorError);
            }
            window.sheetSight.renderState = 'rendered';
            // Notify native immediately. requestAnimationFrame may be throttled by some
            // Android WebView/device combinations and must not control loading state.
            callAndroid('onRendered', currentZoom);
            requestAnimationFrame(restoreReadingPosition);
        } catch (error) {
            reportError(error);
        }
    }

    window.sheetSight = {
        renderState: 'loading',
        renderError: null,
        setZoom(value) {
            if (!osmd) return;
            currentZoom = clamp(Number(value) || 1.0);
            osmd.zoom = currentZoom;
            osmd.render();
            positionCursor(cursorStep);
            callAndroid('onZoomChanged', currentZoom);
        },
        setCursorStep(value) {
            positionCursor(value);
        }
    };

    window.addEventListener('scroll', () => {
        if (scrollTimer !== null) window.clearTimeout(scrollTimer);
        scrollTimer = window.setTimeout(() => {
            const range = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
            const progress = Math.min(1, Math.max(0, window.scrollY / range));
            const system = Math.round(progress * (systemCountHint - 1));
            callAndroid('onSystemChanged', system);
        }, 180);
    }, { passive: true });

    render();
})();
