ARG BASE=python:3.13-slim
FROM ${BASE}
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt
COPY app.py .
RUN useradd -u 10001 -m app && mkdir -p /data && chown app /data
USER app
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]
