FROM node:24-alpine
WORKDIR /app
COPY package.json ./
COPY src ./src
RUN mkdir data && chown node:node data
USER node
ENV HOST=0.0.0.0 PORT=8787 DATA_PATH=/app/data/sideout.sqlite
EXPOSE 8787
CMD ["node", "src/server.js"]
