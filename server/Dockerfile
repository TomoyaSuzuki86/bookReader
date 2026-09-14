FROM node:22-bookworm-slim
WORKDIR /app
COPY package*.json ./
RUN npm install --omit=dev
COPY src ./src
COPY public ./public
RUN mkdir -p /app/data
ENV PORT=8787 DATA_DIR=/app/data
EXPOSE 8787
CMD ["npm","start"]
