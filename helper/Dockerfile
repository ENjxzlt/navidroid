FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
# yt-dlp bewusst ohne feste Version: YouTube ändert sich oft, neue Builds holen den aktuellen Stand.
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py matching.py ./

RUN useradd --uid 1000 --create-home helper
USER helper

ENV STAGING_DIR=/downloads/navidroid VIDEO_DIR=/videos DATA_DIR=/data
EXPOSE 8787
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8787"]
