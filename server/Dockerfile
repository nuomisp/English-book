FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends tzdata ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home companion && mkdir /data && chown companion:companion /data
COPY --chown=companion:companion app.py /app/app.py
USER companion
EXPOSE 8787
CMD ["python", "/app/app.py"]
