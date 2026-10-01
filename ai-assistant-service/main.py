import py_eureka_client.eureka_client as eureka_client
from fastapi import FastAPI
from contextlib import asynccontextmanager
from app.core.config import settings
from app.db.session import engine, Base
from app.models.event_vector import EventVector
from app.api.endpoints import customer
from app.api.endpoints import organizer
from app.services.kafka.consumer import run_consumer_in_background

@asynccontextmanager
async def lifespan(app: FastAPI):
    # create table if not exists
    Base.metadata.create_all(bind=engine)

    # start kafka consumer thread
    run_consumer_in_background()

    # startup: register with Eureka Server.
    print("Registering with Eureka Server...")
    await eureka_client.init_async(
        eureka_server=settings.eureka_server,
        app_name=settings.app_name,
        instance_port=settings.port,
    )
    print("Registration successful!")
    yield
    # shutdown: cancel registration with Eureka Server
    await eureka_client.stop_async()

app = FastAPI(title="AI Assistant Service", lifespan=lifespan)

@app.get("/health")
async def health_check():
    return {"status": "UP", "service": settings.app_name}

app.include_router(
    customer.router,
    prefix="/api/customer",
    tags=["Customer API"]
)

app.include_router(
    organizer.router,
    prefix="/api/organizer",
    tags=["Organizer API"]
)
